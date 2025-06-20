/*
 * Copyright 2022 The Blaze Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.blaze

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable.ArrayBuffer

import org.apache.arrow.c.ArrowArray
import org.apache.arrow.c.ArrowSchema
import org.apache.arrow.c.CDataDictionaryProvider
import org.apache.arrow.c.Data
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.Schema
import org.apache.spark.Partition
import org.apache.spark.TaskContext
import org.apache.spark.internal.Logging
import org.apache.spark.sql.blaze.util.Using
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection
import org.apache.spark.sql.execution.blaze.arrowio.util.ArrowUtils
import org.apache.spark.sql.execution.blaze.arrowio.util.ArrowUtils.ROOT_ALLOCATOR
import org.apache.spark.sql.execution.blaze.columnar.ColumnarHelper
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.CompletionIterator
import org.apache.spark.util.ShutdownHookManager
import org.apache.spark.util.Utils
import org.blaze.protobuf.PartitionId
import org.blaze.protobuf.PhysicalPlanNode
import org.blaze.protobuf.TaskDefinition

case class BlazeCallNativeWrapper(
    nativePlan: PhysicalPlanNode,
    partition: Partition,
    context: Option[TaskContext],
    metrics: MetricNode)
    extends Logging {

  BlazeCallNativeWrapper.initNative()

  private val error: AtomicReference[Throwable] = new AtomicReference(null)
  private val dictionaryProvider = new CDataDictionaryProvider()
  private var arrowSchema: Schema = _
  private var schema: StructType = _
  private var toUnsafe: UnsafeProjection = _
  private val batchRows: ArrayBuffer[InternalRow] = ArrayBuffer()
  private var batchCurRowIdx = 0

  logWarning(s"Start executing native plan")
  private var nativeRuntimePtr = JniBridge.callNative(NativeHelper.nativeMemory, this)

  private lazy val rowIterator = new Iterator[InternalRow] {
    override def hasNext: Boolean = {
      checkError()

      if (batchCurRowIdx < batchRows.length) {
        return true
      }

      // clear current batch
      batchRows.clear()
      batchCurRowIdx = 0

      // load next batch
      try {
        if (nativeRuntimePtr != 0 && JniBridge.nextBatch(nativeRuntimePtr)) {
          return hasNext
        }
      } finally {
        // if error has been set, throw set error instead of this caught exception
        checkError()
      }
      false
    }

    override def next(): InternalRow = {
      val batchRow = batchRows(batchCurRowIdx)
      batchCurRowIdx += 1
      batchRow
    }
  }

  context.foreach(_.addTaskCompletionListener[Unit]((_: TaskContext) => close()))
  context.foreach(_.addTaskFailureListener((_, _) => close()))

  def getRowIterator: Iterator[InternalRow] = {
    CompletionIterator[InternalRow, Iterator[InternalRow]](rowIterator, close())
  }

  protected def getMetrics: MetricNode =
    metrics

  protected def importSchema(ffiSchemaPtr: Long): Unit = {
    Using.resource(ArrowSchema.wrap(ffiSchemaPtr)) { ffiSchema =>
      arrowSchema = Data.importSchema(ROOT_ALLOCATOR, ffiSchema, dictionaryProvider)
      schema = ArrowUtils.fromArrowSchema(arrowSchema)
      toUnsafe = UnsafeProjection.create(schema)
    }
  }

  protected def importBatch(ffiArrayPtr: Long): Unit = {
    if (nativeRuntimePtr == 0) {
      throw new RuntimeException("Native runtime is finalized")
    }

    Using.resources(
      ArrowArray.wrap(ffiArrayPtr),
      VectorSchemaRoot.create(arrowSchema, ROOT_ALLOCATOR)) { case (ffiArray, root) =>
      Data.importIntoVectorSchemaRoot(ROOT_ALLOCATOR, ffiArray, root, dictionaryProvider)

      batchRows.append(
        ColumnarHelper
          .rootRowsIter(root)
          .map(row => toUnsafe(row).copy().asInstanceOf[InternalRow])
          .toSeq: _*)
    }
  }

  protected def setError(error: Throwable): Unit = {
    this.error.set(error)
  }

  protected def checkError(): Unit = {
    val throwable = error.getAndSet(null)
    if (throwable != null) {
      close()
      throw throwable
    }
  }

  protected def getRawTaskDefinition: Array[Byte] = {
    val partitionId: PartitionId = PartitionId
      .newBuilder()
      .setPartitionId(partition.index)
      .setStageId(context.map(_.stageId()).getOrElse(0))
      .setTaskId(context.map(_.taskAttemptId()).getOrElse(0))
      .build()

    val taskDefinition = TaskDefinition
      .newBuilder()
      .setTaskId(partitionId)
      .setPlan(nativePlan)
      .build()
    taskDefinition.toByteArray
  }

  private def close(): Unit = {
    synchronized {
      batchRows.clear()
      batchCurRowIdx = 0

      if (nativeRuntimePtr != 0) {
        JniBridge.finalizeNative(nativeRuntimePtr)
        nativeRuntimePtr = 0
        dictionaryProvider.close()
        checkError()
      }
    }
  }
}

object BlazeCallNativeWrapper extends Logging {
  def initNative(): Unit = {
    lazyInitNative
  }

  private lazy val lazyInitNative: Unit = {
    logInfo(
      "Initializing native environment (" +
        s"batchSize=${BlazeConf.BATCH_SIZE.intConf()}, " +
        s"nativeMemory=${NativeHelper.nativeMemory}, " +
        s"memoryFraction=${BlazeConf.MEMORY_FRACTION.doubleConf()})")

    // arrow configuration
    System.setProperty("arrow.struct.conflict.policy", "CONFLICT_APPEND")

    assert(classOf[JniBridge] != null) // preload JNI bridge classes
    BlazeCallNativeWrapper.loadLibBlaze()
    ShutdownHookManager.addShutdownHook(() => JniBridge.onExit())
  }

  private def loadLibBlaze(): Unit = {
    val libName = System.mapLibraryName("blaze")
    try {
      val classLoader = classOf[NativeSupports].getClassLoader
      val tempFile = File.createTempFile("libblaze-", ".tmp")
      tempFile.deleteOnExit()

      Utils.tryWithResource {
        val is = classLoader.getResourceAsStream(libName)
        assert(is != null, s"cannot load $libName")
        is
      }(Files.copy(_, tempFile.toPath, StandardCopyOption.REPLACE_EXISTING))
      System.load(tempFile.getAbsolutePath)

    } catch {
      case e: IOException =>
        throw new IllegalStateException("error loading native libraries: " + e)
    }
  }
}

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

package org.apache.spark.sql.blaze.shims

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.blaze.RDDShims

private[blaze] class RDDShimsImpl extends RDDShims {
  override def getShuffleReadFull(rdd: RDD[_]): Boolean =
    true

  override def setShuffleReadFull(rdd: RDD[_], shuffleReadFull: Boolean): Unit =
    ()
}
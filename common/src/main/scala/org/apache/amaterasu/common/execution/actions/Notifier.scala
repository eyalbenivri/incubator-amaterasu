/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.amaterasu.common.execution.actions

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.amaterasu.common.execution.actions.NotificationLevel.NotificationLevel
import org.apache.amaterasu.common.execution.actions.NotificationType.NotificationType
import org.apache.amaterasu.common.logging.Logging

abstract class Notifier extends Logging {

  def info(msg: String)

  def success(line: String)

  def error(line: String, msg: String)

}


object NotificationType extends Enumeration {

  type NotificationType = Value
  val success: NotificationType.Value = Value("success")
  val error: NotificationType.Value = Value("error")
  val info: NotificationType.Value = Value("info")

}

object NotificationLevel extends Enumeration {

  type NotificationLevel = Value
  val execution: NotificationLevel.Value = Value("execution")
  val code: NotificationLevel.Value = Value("code")
  val none: NotificationLevel.Value = Value("none")

}

case class Notification(@JsonProperty("line") line: String,
                        @JsonProperty("msg") msg: String,
                        @JsonProperty("notType") notType: NotificationType,
                        @JsonProperty("notLevel") notLevel: NotificationLevel)

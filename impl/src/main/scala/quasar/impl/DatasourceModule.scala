/*
 * Copyright 2020 Precog Data
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

package quasar.impl

import quasar.api.datasource.DatasourceType
import quasar.api.datasource.DatasourceError.PatchingError
import quasar.connector.datasource.{HeavyweightDatasourceModule, LightweightDatasourceModule}

import argonaut.Json

import scalaz.\/

sealed trait DatasourceModule {
  def kind: DatasourceType
  def sanitizeConfig(config: Json): Json

  /* Merges `patch` into `original`, preserving the sensitive
   * components of `original` in the result.
   *
   * If `patch` contains sensitive components, a `PatchContainsSensitiveInfo`
   * error is returned.
   *
   * If `patch` is malformed, a `MalformedPatch` error is returned.
   */
  def patchConfigs(original: Json, patch: Json): PatchingError[Json] \/ Json
}

object DatasourceModule {
  final case class Lightweight(lw: LightweightDatasourceModule) extends DatasourceModule {
    def kind = lw.kind
    def sanitizeConfig(config: Json): Json = lw.sanitizeConfig(config)

    def patchConfigs(original: Json, patch: Json): PatchingError[Json] \/ Json =
      lw.patchConfigs(original, patch)
  }

  final case class Heavyweight(hw: HeavyweightDatasourceModule) extends DatasourceModule {
    def kind = hw.kind
    def sanitizeConfig(config: Json): Json = hw.sanitizeConfig(config)

    def patchConfigs(original: Json, patch: Json): PatchingError[Json] \/ Json =
      hw.patchConfigs(original, patch)
  }
}

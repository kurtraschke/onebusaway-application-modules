/**
 * Copyright (C) 2014 Kurt Raschke <kurt@kurtraschke.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onebusaway.transit_data.model;

import org.onebusaway.gtfs.model.AgencyAndId;

public class AutocompleteResultBean {

  private String key;

  private AgencyAndId result;

  private Object payload;

  public AutocompleteResultBean(String key, AgencyAndId result, Object payload) {
    this.key = key;
    this.result = result;
    this.payload = payload;
  }

  public String getKey() {
    return key;
  }

  public AgencyAndId getResult() {
    return result;
  }

  public Object getPayload() {
    return payload;
  }

}

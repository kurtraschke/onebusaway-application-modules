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
package org.onebusaway.api.actions.api;

import org.onebusaway.geospatial.model.RadiusBounds;
import org.onebusaway.geospatial.model.SearchBounds;
import org.onebusaway.geospatial.services.SphericalGeometryLibrary;

import org.apache.struts2.interceptor.ParameterAware;

import java.util.Map;

public class SpatialActionSupport extends ApiActionSupport implements
    ParameterAware {

  private static final long serialVersionUID = 1L;

  private Map<String, String[]> _parameters;

  public SpatialActionSupport(int defaultVersion) {
    super(defaultVersion);
  }

  @Override
  public void setParameters(Map<String, String[]> parameters) {
    _parameters = parameters;
  }

  protected void validateSpatialArgs() {
    if (_parameters.containsKey("latSpan")
        && !_parameters.containsKey("lonSpan")) {
      addFieldError("lonSpan", "must be specified");
    }

    if (!_parameters.containsKey("latSpan")
        && _parameters.containsKey("lonSpan")) {
      addFieldError("latSpan", "must be specified");
    }

    if (_parameters.containsKey("latSpan")
        && _parameters.containsKey("lonSpan")
        && _parameters.containsKey("radius")) {
      addFieldError("latSpan", "cannot be specified with radius");
      addFieldError("lonSpan", "cannot be specified with radius");
      addFieldError("radius", "cannot be specified with latSpan/lonSpan");
    }

    boolean containsBounds = _parameters.containsKey("latSpan")
        || _parameters.containsKey("lonSpan")
        || _parameters.containsKey("radius");

    if (!_parameters.containsKey("lon")
        && (_parameters.containsKey("lat") || containsBounds)) {
      addFieldError("lon", "must be specified");
    }

    if ((_parameters.containsKey("lon") || containsBounds)
        && !_parameters.containsKey("lat")) {
      addFieldError("lat", "must be specified");
    }

  }

  private double extract(String fieldName) {
    double out = 0;
    String[] parameterValues = _parameters.get(fieldName);

    if (parameterValues.length > 1) {
      addFieldError(fieldName, "must be specified only once");
    }

    try {
      out = Double.parseDouble(parameterValues[0]);
    } catch (NumberFormatException nf) {
      addFieldError(fieldName, "must be numeric");
    }

    return out;
  }

  protected SearchBounds getBounds(double defaultSearchRadius) {
    SearchBounds out;
    double lat = extract("lat");
    double lon = extract("lon");

    if (_parameters.containsKey("latSpan")
        && _parameters.containsKey("lonSpan")) {
      double latSpan = extract("latSpan");
      double lonSpan = extract("lonSpan");

      out = SphericalGeometryLibrary.boundsFromLatLonSpan(lat, lon, latSpan,
          lonSpan);
    } else {
      double radius;

      if (_parameters.containsKey("radius")) {
        radius = extract("radius");
      } else {
        radius = defaultSearchRadius;
      }

      out = new RadiusBounds(lat, lon, radius);
    }

    return out;
  }

}

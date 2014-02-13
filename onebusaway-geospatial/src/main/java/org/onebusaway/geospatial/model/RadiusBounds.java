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

package org.onebusaway.geospatial.model;

import java.io.Serializable;

public class RadiusBounds implements Serializable, SearchBounds {

  private static final long serialVersionUID = 1L;

  private double _lat;
  private double _lon;
  private double _radius;

  public RadiusBounds() {

  }

  public RadiusBounds(double lat, double lon, double radius) {
    _lat = lat;
    _lon = lon;
    _radius = radius;
  }

  public double getLat() {
    return _lat;
  }

  public void setLat(double lat) {
    _lat = lat;
  }

  public double getLon() {
    return _lon;
  }

  public void setLon(double lon) {
    _lon = lon;
  }

  public double getRadius() {
    return _radius;
  }

  public void setRadius(double radius) {
    _radius = radius;
  }

  @Override
  public String toString() {
    return "RadiusBounds [_lat=" + _lat + ", _lon=" + _lon + ", _radius="
        + _radius + "]";
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + new Double(_lat).hashCode();
    result = prime * result + new Double(_lon).hashCode();
    result = prime * result + new Double(_radius).hashCode();
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    RadiusBounds other = (RadiusBounds) obj;
    if (_lat != other._lat)
      return false;
    if (_lon != other._lon)
      return false;
    if (_radius != other._radius)
      return false;
    return true;
  }

  @Override
  public void accept(SearchBoundsVisitor visitor) {
    visitor.visit(this);
  }

}

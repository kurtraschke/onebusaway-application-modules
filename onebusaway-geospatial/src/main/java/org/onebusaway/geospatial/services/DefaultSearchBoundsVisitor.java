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

package org.onebusaway.geospatial.services;

import org.onebusaway.geospatial.DefaultSpatialContext;
import org.onebusaway.geospatial.model.CoordinateBounds;
import org.onebusaway.geospatial.model.RadiusBounds;
import org.onebusaway.geospatial.model.SearchBounds;
import org.onebusaway.geospatial.model.SearchBoundsVisitor;

import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.distance.DistanceUtils;
import com.spatial4j.core.shape.Shape;

public class DefaultSearchBoundsVisitor implements SearchBoundsVisitor {

  private final SpatialContext _spatialContext = DefaultSpatialContext.SPATIAL_CONTEXT;

  private Shape _shape;

  @Override
  public void visit(CoordinateBounds cb) {
    _shape = _spatialContext.makeRectangle(cb.getMinLon(), cb.getMaxLon(),
        cb.getMinLat(), cb.getMaxLat());
  }

  @Override
  public void visit(RadiusBounds rb) {
    _shape = _spatialContext.makeCircle(rb.getLon(), rb.getLat(),
        (rb.getRadius() / 1000.0) * DistanceUtils.KM_TO_DEG);
  }

  public Shape getShape() {
    return _shape;
  }

  public static Shape shape(SearchBounds bounds) {
    DefaultSearchBoundsVisitor visitor = new DefaultSearchBoundsVisitor();
    bounds.accept(visitor);
    return visitor.getShape();
  }

}

/**
 * Copyright (C) 2015 Kurt Raschke <kurt@kurtraschke.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.onebusaway.transit_data_federation.bundle.tasks;

import org.onebusaway.gtfs.services.GenericMutableDao;
import org.onebusaway.transit_data_federation.bundle.model.GtfsBundles;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 *
 * @author kurt
 */
public class GtfsValidationTask implements Runnable {

  private ApplicationContext _applicationContext;
  private FederatedTransitDataBundle _bundle;

  @Autowired
  public void setApplicationContext(ApplicationContext applicationContext) {
    _applicationContext = applicationContext;
  }

  @Autowired
  public void setFederatedTransitDataBundle(FederatedTransitDataBundle bundle) {
    _bundle = bundle;
  }

  @Override
  public void run() {
    //File bundlePath = _bundle.getPath();
    //GtfsBundles bundles = GtfsReadingSupport.getGtfsBundles(_applicationContext);

    ScriptEngine engine = new ScriptEngineManager().getEngineByName("python");

    try {
      engine.eval("import feedvalidator");
    } catch (ScriptException ex) {
      Logger.getLogger(GtfsValidationTask.class.getName()).log(Level.SEVERE, null, ex);
    }
    
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  public static void main(String[] args) {
    new GtfsValidationTask().run();
  }
  
}

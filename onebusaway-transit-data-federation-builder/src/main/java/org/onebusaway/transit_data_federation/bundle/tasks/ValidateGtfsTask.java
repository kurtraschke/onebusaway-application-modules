/**
 * Copyright (C) 2015 Kurt Raschke <kurt@kurtraschke.com>
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
package org.onebusaway.transit_data_federation.bundle.tasks;

import org.onebusaway.transit_data_federation.bundle.model.GtfsBundle;
import org.onebusaway.transit_data_federation.bundle.model.GtfsBundles;

import org.python.core.Py;
import org.python.core.PyBoolean;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 *
 * @author kurt
 */
public class ValidateGtfsTask implements Runnable {

  private static final Logger _log = LoggerFactory.getLogger(ValidateGtfsTask.class);
  private ApplicationContext _applicationContext;

  @Autowired
  public void setApplicationContext(ApplicationContext applicationContext) {
    _applicationContext = applicationContext;
  }

  @Override
  public void run() {
    GtfsBundles bundles = GtfsReadingSupport.getGtfsBundles(_applicationContext);

    for (GtfsBundle bundle : bundles.getBundles()) {
      //GtfsBundle.url isn't actually supported elsewhere;
      //no apparent need to support it here.
      validate(bundle.getPath().getPath());
    }
  }

  private void validate(String feedLocation) {
    try {
      ScriptEngine engine = new ScriptEngineManager().getEngineByName("python");

      engine.eval("import transitfeed");

      PyObject transitfeed = (PyObject) engine.get("transitfeed");

      PyObject problems = transitfeed.invoke("ProblemReporter", Py.java2py(new LoggingProblemAccumulator()));

      PyObject loader = transitfeed.invoke("GetGtfsFactory").invoke("Loader",
              new PyObject[]{new PyString(feedLocation), problems, new PyBoolean(false)},
              new String[]{"problems", "extra_validation"});

      PyObject schedule = loader.invoke("Load");

      schedule.invoke("Validate", new PyObject[]{new PyBoolean(false)}, new String[]{"validate_children"});
    } catch (ScriptException ex) {
      _log.warn("Exception while validating GTFS feed", ex);
    }
  }

  public class LoggingProblemAccumulator {

    public void _Report(PyObject problem) {

      //TODO: actually make use of MDC
      MDC.clear();
      for (String attribute : new String[]{"feed_name", "file_name", "row_num", "column_name"}) {
        PyObject attrValue = problem.__findattr__(attribute);
        if (attrValue != null) {
          MDC.put(attribute, attrValue.toString());
        }
      }

      String formattedProblem = problem.invoke("FormatProblem").asString();

      if ((Boolean) problem.invoke("IsError").__tojava__(Boolean.class)) {
        _log.error(formattedProblem);
      } else if ((Boolean) problem.invoke("IsWarning").__tojava__(Boolean.class)) {
        _log.warn(formattedProblem);
      } else if ((Boolean) problem.invoke("IsNotice").__tojava__(Boolean.class)) {
        _log.info(formattedProblem);
      } else {
        //should not get here
        _log.info(formattedProblem);
      }
    }
  }
}

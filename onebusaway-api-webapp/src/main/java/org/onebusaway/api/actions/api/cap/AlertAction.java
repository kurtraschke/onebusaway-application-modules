/**
 * Copyright (C) 2013 Kurt Raschke
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

package org.onebusaway.api.actions.api.cap;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.datatype.DatatypeConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Date;

import org.apache.struts2.convention.annotation.Result;
import org.apache.struts2.rest.DefaultHttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;

import com.opensymphony.xwork2.Action;
import com.opensymphony.xwork2.conversion.annotations.TypeConversion;
import com.opensymphony.xwork2.validator.annotations.RequiredFieldValidator;
import org.onebusaway.api.actions.api.ApiActionSupport;
import org.onebusaway.api.impl.cap.CapSupport;
import org.onebusaway.transit_data.model.service_alerts.ServiceAlertBean;
import org.onebusaway.transit_data.services.TransitDataService;

import oasis.names.tc.emergency.cap._1.Alert;

/**
 *
 * @author kurt
 */
@Result(name = "success", type = "stream", params = {"contentType", "application/cap+xml"})
public class AlertAction extends ApiActionSupport {
  private static final long serialVersionUID = 1L;

  private static final int V2 = 2;

  @Autowired
  private TransitDataService _service;

  @Autowired
  private CapSupport _capSupport;

  private String _alertId;

  private long _time;

  private InputStream inputStream;

  public AlertAction() {
    super(V2);
  }

  public void setTransitDataService(TransitDataService service) {
    _service = service;
  }

  public void setCapSupport(CapSupport support) {
    _capSupport = support;
  }

  @RequiredFieldValidator
  public void setId(String id) {
    _alertId = id;
  }

  public String getId() {
    return _alertId;
  }

  @TypeConversion(converter = "org.onebusaway.presentation.impl.conversion.DateTimeConverter")
  public void setTime(Date time) {
    _time = time.getTime();
  }

  public InputStream getInputStream() {
    return inputStream;
  }

  public DefaultHttpHeaders show() throws DatatypeConfigurationException, JAXBException {
    if (!isVersion(V2)) {
      return setUnknownVersionResponse();
    }

    if (hasErrors()) {
      return setValidationErrorsResponse();
    }

    long time = System.currentTimeMillis();
    if (_time != 0) {
      time = _time;
    }

    ServiceAlertBean alert = _service.getServiceAlertForId(_alertId);

    if (alert == null) {
      return setResourceNotFoundResponse();
    }

    Alert capAlert = _capSupport.buildCapAlert(alert);

    ByteArrayOutputStream os = new ByteArrayOutputStream();

    JAXBContext context = JAXBContext.newInstance(Alert.class);
    Marshaller m = context.createMarshaller();
    m.marshal(capAlert, os);

    inputStream = new ByteArrayInputStream(os.toByteArray());
        
    return new DefaultHttpHeaders(Action.SUCCESS);
  }
}

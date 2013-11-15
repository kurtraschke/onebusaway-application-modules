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

import org.onebusaway.api.impl.cap.CapSupport;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.datatype.DatatypeConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.List;

import org.apache.struts2.convention.annotation.Result;
import org.apache.struts2.rest.DefaultHttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.w3._2005.atom.ContentType;
import org.w3._2005.atom.EntryType;
import org.w3._2005.atom.FeedType;
import org.w3._2005.atom.IdType;
import org.w3._2005.atom.ObjectFactory;

import com.opensymphony.xwork2.Action;
import com.opensymphony.xwork2.conversion.annotations.TypeConversion;
import com.opensymphony.xwork2.validator.annotations.RequiredFieldValidator;
import org.onebusaway.api.actions.api.ApiActionSupport;
import org.onebusaway.exceptions.ServiceException;
import org.onebusaway.transit_data.model.ListBean;
import org.onebusaway.transit_data.model.service_alerts.ServiceAlertBean;
import org.onebusaway.transit_data.services.TransitDataService;

import oasis.names.tc.emergency.cap._1.Alert;

/**
 *
 * @author kurt
 */
@Result(name="success", type="stream", params={"contentType", "application/atom+xml"})
public class AlertsForAgencyAction extends ApiActionSupport {

  private static final long serialVersionUID = 1L;

  private static final int V2 = 2;
  
  @Autowired
  private TransitDataService _service;
  
  @Autowired
  private CapSupport _capSupport;

  private String _agencyId;

  private long _time;
  
  private InputStream inputStream;
  
  private ObjectFactory atomFactory = new ObjectFactory();
  
  public AlertsForAgencyAction() {
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
    _agencyId = id;
  }

  public String getId() {
    return _agencyId;
  }

  @TypeConversion(converter = "org.onebusaway.presentation.impl.conversion.DateTimeConverter")
  public void setTime(Date time) {
    _time = time.getTime();
  }
  
  public InputStream getInputStream() {
    return inputStream;
  }
  
  public DefaultHttpHeaders show() throws ServiceException, DatatypeConfigurationException, JAXBException {
    if (!isVersion(V2))
      return setUnknownVersionResponse();

    if (hasErrors())
      return setValidationErrorsResponse();

    long time = System.currentTimeMillis();
    if (_time != 0)
      time = _time;

    
    List<ServiceAlertBean> serviceAlerts = _service.getAllServiceAlertsForAgencyId(_agencyId).getList();

    FeedType alertsFeed = fillFeed(serviceAlerts);
    
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    
    JAXBContext context = JAXBContext.newInstance(Alert.class, FeedType.class);
    Marshaller m = context.createMarshaller();
    m.marshal(atomFactory.createFeed(alertsFeed), os);
            
    inputStream = new ByteArrayInputStream(os.toByteArray());
    
    return new DefaultHttpHeaders(Action.SUCCESS);
  }
  
  
  private FeedType fillFeed(List<ServiceAlertBean> serviceAlerts) throws DatatypeConfigurationException {
    FeedType theFeed = atomFactory.createFeedType();
    
    //generator, updated, author, title
    
    for(ServiceAlertBean alert: serviceAlerts) {
      EntryType theEntry = buildEntry(alert);
      
      theFeed.getAuthorOrCategoryOrContributor().add(atomFactory.createEntry(theEntry));
    }
    
    return theFeed;
  }

  private EntryType buildEntry(ServiceAlertBean alert) throws DatatypeConfigurationException {
EntryType theEntry = atomFactory.createEntryType();
    
    Alert capAlert = _capSupport.buildCapAlert(alert);
    
    //id, updated, published, author, title, link, summary, CAP

    
    
    IdType theId = atomFactory.createIdType();
   
    theId.setValue(capAlert.getIdentifier());
    theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypeId(theId));
    
    
ContentType capContent = atomFactory.createContentType();

capContent.setType("application/cap+xml");
capContent.getContent().add(capAlert);

theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypeContent(capContent));
    

    return theEntry;

  }

}

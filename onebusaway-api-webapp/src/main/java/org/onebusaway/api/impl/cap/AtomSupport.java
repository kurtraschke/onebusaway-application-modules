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

package org.onebusaway.api.impl.cap;

import javax.servlet.http.HttpServletRequest;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeConfigurationException;

import java.util.ArrayList;
import java.util.List;

import org.apache.struts2.ServletActionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3._2005.atom.ContentType;
import org.w3._2005.atom.DateTimeType;
import org.w3._2005.atom.EntryType;
import org.w3._2005.atom.FeedType;
import org.w3._2005.atom.GeneratorType;
import org.w3._2005.atom.IdType;
import org.w3._2005.atom.LinkType;
import org.w3._2005.atom.ObjectFactory;
import org.w3._2005.atom.PersonType;
import org.w3._2005.atom.TextType;
import org.w3._2005.atom.UriType;

import org.onebusaway.collections.Max;
import org.onebusaway.transit_data.model.AgencyBean;
import org.onebusaway.transit_data.model.service_alerts.NaturalLanguageStringBean;
import org.onebusaway.transit_data.model.service_alerts.ServiceAlertBean;
import org.onebusaway.transit_data.services.TransitDataService;

import oasis.names.tc.emergency.cap._1.Alert;

@Component
public class AtomSupport {

  @Autowired
  private CapSupport _capSupport;

  private ObjectFactory atomFactory = new ObjectFactory();

  public JAXBElement<FeedType> fillFeed(AgencyBean ag, List<ServiceAlertBean> serviceAlerts) throws DatatypeConfigurationException {
    FeedType theFeed = atomFactory.createFeedType();

    GeneratorType g = atomFactory.createGeneratorType();
    g.setValue("OneBusAway");
    g.setUri("http://onebusaway.org");

    theFeed.getAuthorOrCategoryOrContributor().add(atomFactory.createFeedTypeGenerator(g));

    PersonType pt = atomFactory.createPersonType();
    pt.getNameOrUriOrEmail().add(atomFactory.createPersonTypeName(ag.getName()));

    UriType ut = atomFactory.createUriType();
    ut.setValue(ag.getUrl());
    pt.getNameOrUriOrEmail().add(atomFactory.createPersonTypeUri(ut));

    theFeed.getAuthorOrCategoryOrContributor().add(atomFactory.createFeedTypeAuthor(pt));

    TextType title = atomFactory.createTextType();
    title.getContent().add(ag.getName() + " Alerts");
    theFeed.getAuthorOrCategoryOrContributor().add(atomFactory.createFeedTypeTitle(title));
    
    IdType feedId = atomFactory.createIdType();
    feedId.setValue(findBaseUrl() + "/api/cap/alerts-for-agency/" + ag.getId());
    theFeed.getAuthorOrCategoryOrContributor().add(atomFactory.createFeedTypeId(feedId));
    
    Max<ServiceAlertBean> m = new Max<ServiceAlertBean>();

    List<JAXBElement> entries = new ArrayList<JAXBElement>();
    
    for (ServiceAlertBean alert : serviceAlerts) {
      EntryType theEntry = buildEntry(alert);

      m.add(alert.getCreationTime(), alert);

      entries.add(atomFactory.createEntry(theEntry));
    }

    DateTimeType dtt = atomFactory.createDateTimeType();
    dtt.setValue(XmlSupport.makeXmlGregorianCalendar((m.getMaxElement() != null && m.getMaxElement().getCreationTime() > 0) ?  m.getMaxElement().getCreationTime() : System.currentTimeMillis()));
    theFeed.getAuthorOrCategoryOrContributor().add(atomFactory.createFeedTypeUpdated(dtt));

    theFeed.getAuthorOrCategoryOrContributor().addAll(entries);
    
    return atomFactory.createFeed(theFeed);
  }

  private EntryType buildEntry(ServiceAlertBean alert) throws DatatypeConfigurationException {
    EntryType theEntry = atomFactory.createEntryType();

    Alert capAlert = _capSupport.buildCapAlert(alert);

    String entryCapLink = findBaseUrl() + "/api/cap/alert/" + alert.getId();
    
    IdType theId = atomFactory.createIdType();
    theId.setValue(entryCapLink);
    theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypeId(theId));

    DateTimeType dtt = atomFactory.createDateTimeType();
    dtt.setValue(capAlert.getSent());

    theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypeUpdated(dtt));
    theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypePublished(dtt));


    if (alert.getSummaries() != null && !alert.getSummaries().isEmpty()) {
      NaturalLanguageStringBean summary = alert.getSummaries().get(0);
      String summaryText = summary.getValue();
      String summaryLang = summary.getLang();

      TextType titleTextType = atomFactory.createTextType();
      titleTextType.getContent().add(summaryText);
      titleTextType.setLang(summaryLang);
      titleTextType.setType("text");
      theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypeTitle(titleTextType));
    }

    if (alert.getDescriptions() != null && !alert.getDescriptions().isEmpty()) {
      NaturalLanguageStringBean description = alert.getDescriptions().get(0);
      String descriptionText = description.getValue();
      String descriptionLang = description.getLang();

      TextType summaryTextType = atomFactory.createTextType();
      summaryTextType.getContent().add(descriptionText);
      summaryTextType.setLang(descriptionLang);
      summaryTextType.setType("text");
      theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypeSummary(summaryTextType));
    }

    if (alert.getUrls() != null && !alert.getUrls().isEmpty()) {
      for (NaturalLanguageStringBean url : alert.getUrls()) {
        LinkType lt = atomFactory.createLinkType();

        lt.setHref(url.getValue());
        lt.setHreflang(url.getLang());
        lt.setType("text/html");

        theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypeLink(lt));
      }
    }

    LinkType lt = atomFactory.createLinkType();
    lt.setHref(entryCapLink);
    lt.setType("application/cap+xml");
        
    theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypeLink(lt));
    
    
    ContentType capContent = atomFactory.createContentType();
    capContent.setType("application/cap+xml");
    capContent.getContent().add(capAlert);
    theEntry.getAuthorOrCategoryOrContent().add(atomFactory.createEntryTypeContent(capContent));

    return theEntry;
  }

  
  private String findBaseUrl() {
    HttpServletRequest request = ServletActionContext.getRequest();

    StringBuilder b = new StringBuilder();
    b.append(request.getScheme());
    b.append("://");
    b.append(request.getServerName());
    if (request.getServerPort() != 80) {
      b.append(":").append(request.getServerPort());
    }
    if (request.getContextPath() != null) {
      b.append(request.getContextPath());
    }
    String baseUrl = b.toString();

    return baseUrl;
  }

  public AtomSupport() {
  }
}
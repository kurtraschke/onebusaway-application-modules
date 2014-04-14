package org.onebusaway.transit_data.model;

public class AutocompleteResultBean {

  private String key;

  private Object payload;

  public AutocompleteResultBean(String key, Object payload) {
    this.key = key;
    this.payload = payload;
  }

  public String getKey() {
    return key;
  }

  public Object getPayload() {
    return payload;
  }

}

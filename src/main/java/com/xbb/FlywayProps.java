package com.xbb;

public class FlywayProps {
    private String url;
    private String user;
    private String password;
    private String schemas;
    private String locations;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getSchemas() { return schemas; }
    public void setSchemas(String schemas) { this.schemas = schemas; }
    public String getLocations() { return locations; }
    public void setLocations(String locations) { this.locations = locations; }
}

package com.xbb.baojing.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String jwtSecret;
    private String adminPassword;
    private String enterprisePassword;
    private String integrationMode = "mock";
    private String environment = "development";
    private String uploadsDir = "./uploads";
    private String webDistDir = "../web/dist";
    private String corsOrigins = "*";

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String v) { this.jwtSecret = v; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String v) { this.adminPassword = v; }
    public String getEnterprisePassword() { return enterprisePassword; }
    public void setEnterprisePassword(String v) { this.enterprisePassword = v; }
    public String getIntegrationMode() { return integrationMode; }
    public void setIntegrationMode(String v) { this.integrationMode = v; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String v) { this.environment = v; }
    public String getUploadsDir() { return uploadsDir; }
    public void setUploadsDir(String v) { this.uploadsDir = v; }
    public String getWebDistDir() { return webDistDir; }
    public void setWebDistDir(String v) { this.webDistDir = v; }
    public String getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(String v) { this.corsOrigins = v; }
}

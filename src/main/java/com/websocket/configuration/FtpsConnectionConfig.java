package com.websocket.configuration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ftps.config-map")
public class FtpsConnectionConfig implements Serializable {
    public String host;
    public int port;
    public String username;
    public String password;
    private boolean useImplicit;
    private boolean requireClientCert;
    public int sessionTimeout;
    public int dataTimeout;
    public String READ_ROOT_PATH;
}

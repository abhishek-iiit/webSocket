package com.websocket.services;
import com.websocket.configuration.FtpsConnectionConfig;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Service
public class FTPSServiceUtils {
    private static org.slf4j.Logger logger = LoggerFactory.getLogger(FTPSServiceUtils.class);

    public FTPSClient createFtpsClient(FtpsConnectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("FtpsConnectionConfig cannot be null");
        }

        FTPSClient ftpsClient = new FTPSClient(config.isUseImplicit() ? "TLS" : "SSL");
        try {
            validateConfig(config);

            ftpsClient.connect(config.getHost(), config.getPort());

            ftpsClient.setSoTimeout(config.getSessionTimeout());
            ftpsClient.setDataTimeout(config.getDataTimeout());

            boolean loggedIn = ftpsClient.login(config.getUsername(), config.getPassword());
            if (!loggedIn) {
                throw new IOException("FTPS login failed for user: " + config.getUsername());
            }

            ftpsClient.execPBSZ(0);
            ftpsClient.execPROT("P");
            ftpsClient.setFileType(FTPSClient.BINARY_FILE_TYPE);

            ftpsClient.enterLocalPassiveMode();
            return ftpsClient;

        } catch (IOException e) {
            logger.error("Failed to create FTPS Client: {}", e.getMessage(), e);
            disconnectClientSafely(ftpsClient);
            throw new RuntimeException("FTPS Client setup failed", e);
        }
    }

    private void validateConfig(FtpsConnectionConfig config) {
        if (config.getHost() == null || config.getHost().isEmpty()) {
            throw new IllegalArgumentException("FTPS host must not be null or empty");
        }
        if (config.getPort() <= 0) {
            throw new IllegalArgumentException("FTPS port must be greater than 0");
        }
        if (config.getUsername() == null || config.getUsername().isEmpty()) {
            throw new IllegalArgumentException("FTPS username must not be null or empty");
        }
        if (config.getPassword() == null || config.getPassword().isEmpty()) {
            throw new IllegalArgumentException("FTPS password must not be null or empty");
        }
    }

    public void disconnectClientSafely(FTPSClient ftpsClient) {
        try {
            if (ftpsClient != null && ftpsClient.isConnected()) {
                ftpsClient.logout();
                ftpsClient.disconnect();
                logger.info("Disconnected FTPS Client");
            }
        } catch (IOException ex) {
            logger.error("FTPS disconnect error: {}", ex);
        }
    }

    public Map<String, InputStream> fetchMultipleFiles(List<String> remoteFilePaths, FTPSClient ftpsClient) {
        Map<String, InputStream> fileStreams = new HashMap<>();

        try {
            for (String remoteFilePath : remoteFilePaths) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                if (ftpsClient.retrieveFile(remoteFilePath, outputStream)) {
                    fileStreams.put(remoteFilePath, new ByteArrayInputStream(outputStream.toByteArray()));
                } else {
                    logger.error("Failed to retrieve file: {}", remoteFilePath);
                    fileStreams.put(remoteFilePath, null);
                }
            }
        } catch (IOException ex) {
            logger.error("Error retrieving files: {}", ex.getMessage(), ex);
        }
        return fileStreams;
    }

    public List<String> lsFilesAtLocation(String remoteFilePath, FTPSClient ftpsClient) {
        List<String> fileNames = new ArrayList<>();
        if (Objects.nonNull(ftpsClient)) {
            try {
                if (!ftpsClient.changeWorkingDirectory(remoteFilePath)) {
                    logger.error("Failed to change working directory to {}", remoteFilePath);
                    return fileNames;
                }

                FTPFile[] files = ftpsClient.listFiles();
                if (files != null) {
                    for (FTPFile file : files) {
                        if (file.isDirectory() && !file.getName().equals(".") && !file.getName().equals("..") && !file.getName().equals(".DS_Store")) {
                            fileNames.add(file.getName());
                        }
                    }
                }
            } catch (IOException ex) {
                logger.error("Error listing files at location {}: {}", remoteFilePath, ex);
            }
        }
        return fileNames;
    }
}
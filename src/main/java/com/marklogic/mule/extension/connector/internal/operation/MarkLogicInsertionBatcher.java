/**
 * MarkLogic Mule Connector
 *
 * Copyright © 2019 MarkLogic Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 *
 * This project and its code and functionality is not representative of MarkLogic Server and is not supported by MarkLogic.
 */
package com.marklogic.mule.extension.connector.internal.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.datamovement.*;
import com.marklogic.client.document.DocumentDescriptor;
import com.marklogic.client.document.JSONDocumentManager;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.client.io.InputStreamHandle;
import com.marklogic.client.io.JacksonHandle;
import com.marklogic.mule.extension.connector.api.operation.MarkLogicExistingJobReportDocumentStrategy;
import com.marklogic.mule.extension.connector.internal.config.MarkLogicConfiguration;
import com.marklogic.mule.extension.connector.internal.connection.MarkLogicConnection;

import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;

import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by jkrebs on 9/12/2018. Singleton class that manages inserting
 * documents into MarkLogic
 */
public class MarkLogicInsertionBatcher implements MarkLogicConnectionInvalidationListener
{

    private static final Logger logger = LoggerFactory.getLogger(MarkLogicInsertionBatcher.class);

    private static final DateTimeFormatter ISO8601_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    // The single instance of this class
    private static MarkLogicInsertionBatcher instance;
    private String jobReportUri;
    private ObjectMapper jsonFactory = new ObjectMapper();

    // If support for multiple connection configs within a flow is required, remove the above and uncomment the below.
    // private static Map<String,MarkLogicInsertionBatcher> instances = new HashMap<>();
    // Object that describes the metadata for documents being inserted
    private DocumentMetadataHandle metadataHandle;

    // How will we know when the resources are ready to be freed up and provide the results report?
    private JobTicket jobTicket;
    private final String jobName;
    private JSONDocumentManager jobReportDocumentManager = null;
    private DocumentDescriptor jobReportDocumentDescriptor;
    private MarkLogicExistingJobReportDocumentStrategy existingJobReportDocumentStrategy;
    private boolean jobReportBatcherInitialized = false;


    private JacksonHandle jacksonHandle = new JacksonHandle();

    // The object that actually write record to ML
    private WriteBatcher batcher;

    // Handle for DMSDK Data Movement Manager
    private DataMovementManager dmm;

    // The timestamp of the last write to ML-- used to determine when the pipe to ML should be flushed
    private long lastWriteTime;

    private boolean batcherRequiresReinit = false;
    private MarkLogicConnection connection;
    private Timer timer = null;

    /**
     * Private constructor-- enforces singleton pattern
     * @param configuration -- information describing how the insertion process
     * should work
     * @param connection -- information describing how to connect to MarkLogic
     * @param existingJobReportDocumentStrategy -- how to handle the case when the job report document is already present when the flow starts
     */
    private MarkLogicInsertionBatcher(MarkLogicConfiguration configuration, MarkLogicConnection connection, String outputCollections, String outputPermissions, int outputQuality, String jobName, String temporalCollection, String jobReportUri, MarkLogicExistingJobReportDocumentStrategy existingJobReportDocumentStrategy)
    {
        // get the object handles needed to talk to MarkLogic
        initializeBatcher(connection, configuration, outputCollections, outputPermissions, outputQuality, temporalCollection,jobReportUri, existingJobReportDocumentStrategy);
        this.jobName = jobName;
        this.jobReportUri = jobReportUri;
        this.existingJobReportDocumentStrategy = existingJobReportDocumentStrategy;
    }

    private void initializeBatcher(MarkLogicConnection connection, MarkLogicConfiguration configuration, String outputCollections, String outputPermissions, int outputQuality, String temporalCollection, String jobReportUri, MarkLogicExistingJobReportDocumentStrategy existingJobReportDocumentStrategy)
    {
        this.connection = connection;
        this.jobReportUri = jobReportUri;
        connection.addMarkLogicClientInvalidationListener(this);
        DatabaseClient myClient = connection.getClient();
        dmm = myClient.newDataMovementManager();
        batcher = dmm.newWriteBatcher();
        this.existingJobReportDocumentStrategy = existingJobReportDocumentStrategy;
        // Configure the batcher's behavior
        batcher.withBatchSize(configuration.getBatchSize())
                .withThreadCount(configuration.getThreadCount())
                .onBatchSuccess((batch) ->
                {
                })
                .onBatchFailure((batch, throwable) ->
                {
                    logger.error("Exception thrown by an onBatchSuccess listener", throwable);  // For Sonar...
                });
        // Configure the transform to be used, if any
        // ASSUMPTION: The same transform (or lack thereof) will be used for every document to be inserted during the
        // lifetime of this object

        if (jobReportUri != null && !("null".equals(jobReportUri)))
        {
            initializeJobReportBatcher();
        }
        if ((temporalCollection != null) && !"null".equalsIgnoreCase(temporalCollection))
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("TEMPORAL COLLECTION: " + temporalCollection);
            }
            batcher.withTemporalCollection(temporalCollection);
        }

        if (configuration.hasServerTransform())
        {
            batcher.withTransform(configuration.createServerTransform());
            logger.info("Transforming input doc payload with transform: " + configuration.getServerTransform());
        }
        else
        {
            logger.info("Ingesting doc payload without a transform");
        }

        // Set up the timer to flush the pipe to MarkLogic if it's waiting to long
        int secondsBeforeFlush = configuration.getSecondsBeforeFlush();

        if (timer != null)
        {
            timer.cancel();
        }
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask()
        {
            @Override
            public void run()
            {
                // Check to see if the pipe has been inactive longer than the wait time
                if ((System.currentTimeMillis() - lastWriteTime) >= secondsBeforeFlush * 1000)
                {
                    // if it has, flush the pipe
                    batcher.flushAndWait();
                    // Set the last write time to be something well into the future, so that we don't needlessly,
                    // repeatedly flush the queue
                    lastWriteTime = System.currentTimeMillis() + 900000;
                }
            }
        }, (secondsBeforeFlush * 1000), secondsBeforeFlush * 1000);

        // Set up the metadata to be used for the documents that will be inserted
        // ASSUMPTION: The same metadata will be used for every document to be inserted during the lifetime of this
        // object
        this.metadataHandle = new DocumentMetadataHandle();
        String[] configCollections = outputCollections.split(",");

        // Set up list of collections that new docs should be put into
        if (!configCollections[0].equals("null"))
        {
            metadataHandle.withCollections(configCollections);
        }
        // Set up quality new docs should have
        metadataHandle.setQuality(outputQuality);

        // Set up list of permissions that new docs should be granted
        String[] permissions = outputPermissions.split(",");
        for (int i = 0; i < permissions.length - 1; i++)
        {
            String role = permissions[i];
            String capability = permissions[i + 1];
            switch (capability.toLowerCase())
            {
                case "read":
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.READ);
                    break;
                case "insert":
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.INSERT);
                    break;
                case "update":
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.UPDATE);
                    break;
                case "execute":
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.EXECUTE);
                    break;
                case "node_update":
                    metadataHandle.getPermissions().add(role, DocumentMetadataHandle.Capability.NODE_UPDATE);
                    break;
                default:
                    logger.info("No additive permissions assigned");
            }
        }

        // start the batcher job
        this.jobTicket = dmm.startJob(batcher);
    }

    /**
     * Creates a JSON object containing details about the batcher job
     *
     * @return Job results report
     */
    ObjectNode createJsonJobReport()
    {
        ObjectNode obj = jsonFactory.createObjectNode();
        long successBatches = 0;
        long successEvents = 0;
        long failBatches = 0;
        long failEvents = 0;
        ZonedDateTime jobStartTime = null;
        String jobID = null;

        if (jobReportDocumentManager != null)
        {
            JacksonHandle jrh = null;
            if (jobReportDocumentManager.exists(jobReportUri) != null)
            {
                try {
                    jrh = jobReportDocumentManager.read(jobReportDocumentDescriptor, jacksonHandle);
                } catch (IllegalStateException e)
                {
                    initializeJobReportBatcher();
                    jrh = jobReportDocumentManager.read(jobReportDocumentDescriptor, jacksonHandle);
                }
            }
            if (jrh != null)
            {
                JsonNode doc = jrh.get();
                JsonNode instance = doc.get("envelope").get("instance");
                if (instance != null)
                {
                    jobID = instance.get("jobID").asText();
                    if (instance.get("jobStartTime") != null)
                    {
                        jobStartTime = ZonedDateTime.parse(instance.get("jobStartTime").asText());
                    }
                    if (instance.get("successfulBatches") != null)
                    {
                        successBatches = instance.get("successfulBatches").asLong();
                    }
                    if (instance.get("successfulEvents") != null)
                    {
                        successEvents = instance.get("successfulEvents").asLong();
                    }
                    if (instance.get("failedBatches") != null)
                    {
                        failBatches = instance.get("failedBatches").asLong();
                    }
                    if (instance.get("failedEvents") != null)
                    {
                        failEvents = instance.get("failedEvents").asLong();
                    }
                } else {
                    logger.warn("Failed to parse Job Report document properly: " + jrh.toString());
                }
            }
        }
        JobReport jr = dmm.getJobReport(jobTicket);
        if (jobID == null)
        {
            jobID = jobTicket.getJobId();
        }
        obj.put("jobID", jobID);
        if (jobStartTime == null)
        {
            jobStartTime = toZonedDateTime(jr.getJobStartTime());
        }
        ZonedDateTime jobEndTime = toZonedDateTime(jr.getJobEndTime());
        ZonedDateTime jobReportTime = toZonedDateTime(jr.getReportTimestamp());
        successBatches += jr.getSuccessBatchesCount();
        successEvents += jr.getSuccessEventsCount();
        failBatches += jr.getFailureBatchesCount();
        failEvents += jr.getFailureEventsCount();
        if (failEvents > 0)
        {
            obj.put("jobOutcome", "failed");
        }
        else
        {
            obj.put("jobOutcome", "successful");
        }
        obj.put("successfulBatches", successBatches);
        obj.put("successfulEvents", successEvents);
        obj.put("failedBatches", failBatches);
        obj.put("failedEvents", failEvents);
        obj.put("jobName", jobName);
        obj.put("jobStartTime", jobStartTime.format(ISO8601_DATE_TIME_FORMATTER));
        obj.put("jobEndTime", jobEndTime.format(ISO8601_DATE_TIME_FORMATTER));
        obj.put("jobReportTime", jobReportTime.format(ISO8601_DATE_TIME_FORMATTER));
        return obj;
    }

    /**
     * getInstance-- used in lieu of a public constructor... enforces singleton
     * pattern
     *
     * @param config -- information describing how the insertion process should
     * work
     * @param connection -- information describing how to connect to MarkLogic
     * @param temporalCollection -- name of temporal collection to be used, if there is one
     * @return instance of the batcher
     */
    static MarkLogicInsertionBatcher getInstance(MarkLogicConfiguration config, MarkLogicConnection connection, String outputCollections, String outputPermissions, int outputQuality, String jobName, String temporalCollection, String jobReportUri, MarkLogicExistingJobReportDocumentStrategy existingJobReportDocumentStrategy)    {
        // String configId = config.getConfigId();
        // MarkLogicInsertionBatcher instance = instances.get(configId);
        // Uncomment above to support multiple connection config scenario
        if (instance == null)
        {
            instance = new MarkLogicInsertionBatcher(config, connection, outputCollections, outputPermissions, outputQuality, jobName, temporalCollection, jobReportUri, existingJobReportDocumentStrategy);
            // instances.put(configId,instance);
            // Uncomment above to support multiple connection config scenario
        }
        else if ((!(connection == null)) && (!connection.equals(instance.connection)))
        {
            if (instance.batcherRequiresReinit)
            {
                instance.initializeBatcher(connection, config, outputCollections, outputPermissions, outputQuality, temporalCollection, jobReportUri, existingJobReportDocumentStrategy);
                instance.batcherRequiresReinit = false;
            }
        }
        return instance;
    }

    /**
     * getInstance method to be used when configuration objects aren't available
     *
     * @return instance of the batcher
     */
    static MarkLogicInsertionBatcher getInstance()
    {
        return instance;
    }

    /**
     * Actually does the work of passing the document on to DMSDK to do its
     * thing
     *
     * @param outURI -- the URI to be used for the document being inserted
     * @param documentStream -- the InputStream containing the document to be
     * inserted... comes from mule
     * @return jobTicketID
     */
    String doInsert(String outURI, InputStream documentStream)
    {
        // Add the InputStream to the DMSDK WriteBatcher object
        batcher.addAs(outURI, metadataHandle, new InputStreamHandle(documentStream));
        // Update the most recent insert's timestamp
        lastWriteTime = System.currentTimeMillis();
        // Have the DMSDK WriteBatcher object sleep until it is needed again
        batcher.awaitCompletion();
        // Return the job ticket ID so it can be used to retrieve the document in the future
        return jobTicket.getJobId();
    }

    private ZonedDateTime toZonedDateTime(Calendar calendar)
    {
        if (calendar == null)
        {
            return ZonedDateTime.now();
        }
        TimeZone tz = calendar.getTimeZone();
        ZoneId zid = tz == null ? ZoneId.systemDefault() : tz.toZoneId();
        return ZonedDateTime.ofInstant(calendar.toInstant(), zid);
    }

    @Override
    public void markLogicConnectionInvalidated()
    {
        // Save the job results to MarkLogic if a URI is specified for that purpose
        if (jobReportDocumentManager != null)
        {
            ObjectNode jobReport = createJsonJobReport();
            batcher.addAs(jobReportUri,metadataHandle,new JacksonHandle(jobReport));
            batcher.flushAndWait();
        }
        logger.info("MarkLogic connection invalidated... reinitializing insertion batcher...");
        batcherRequiresReinit = true;
    }

    /**
     * This function gets called under 2 conditions:
     *   -When the flow first starts
     *   -If the connection has been invalidated and then re-opened
     */
    private void initializeJobReportBatcher ()
    {
        jobReportDocumentManager = connection.getClient(!(jobReportDocumentManager == null)).newJSONDocumentManager();
        jobReportDocumentManager.setPageLength(1);
        jobReportDocumentDescriptor = jobReportDocumentManager.newDescriptor(jobReportUri);
        // If this is the start of the flow, and we're using an overwrite strategy, delete the job report document, if it exists
        if ((existingJobReportDocumentStrategy == MarkLogicExistingJobReportDocumentStrategy.OVERWRITE) && (!jobReportBatcherInitialized) && (jobReportDocumentManager.exists(jobReportUri) != null))  {
            jobReportDocumentManager.delete(jobReportDocumentDescriptor);
        }
        jobReportBatcherInitialized = true;
    }
}

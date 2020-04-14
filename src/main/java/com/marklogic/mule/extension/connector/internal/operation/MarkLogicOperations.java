/**
 * MarkLogic Mule Connector
 *
 * Copyright © 2020 MarkLogic Corporation.
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

import com.marklogic.mule.extension.connector.api.MarkLogicAttributes;
import com.marklogic.mule.extension.connector.api.operation.MarkLogicQueryFormat;
import com.marklogic.mule.extension.connector.api.operation.MarkLogicQueryStrategy;
import java.io.InputStream;

import java.util.*;

import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.datamovement.DataMovementManager;
import com.marklogic.client.datamovement.DeleteListener;
import com.marklogic.client.datamovement.QueryBatcher;
import com.marklogic.client.io.*;
import com.marklogic.client.query.QueryDefinition;
import com.marklogic.client.query.QueryManager;

import com.marklogic.mule.extension.connector.internal.config.MarkLogicConfiguration;
import com.marklogic.mule.extension.connector.internal.connection.MarkLogicConnector;
import com.marklogic.mule.extension.connector.internal.error.provider.MarkLogicExecuteErrorsProvider;

import static org.mule.runtime.extension.api.annotation.param.MediaType.ANY;
import static org.mule.runtime.extension.api.annotation.param.MediaType.APPLICATION_JSON;

import com.marklogic.mule.extension.connector.internal.metadata.MarkLogicSelectMetadataResolver;
import com.marklogic.mule.extension.connector.internal.result.resultset.MarkLogicExportListener;
import com.marklogic.mule.extension.connector.internal.result.resultset.MarkLogicResultSetCloser;
import com.marklogic.mule.extension.connector.internal.result.resultset.MarkLogicResultSetIterator;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.extension.api.annotation.error.Throws;
import org.mule.runtime.extension.api.annotation.metadata.OutputResolver;
import org.mule.runtime.extension.api.annotation.param.MediaType;
import org.mule.runtime.extension.api.annotation.param.Config;
import org.mule.runtime.extension.api.annotation.param.Content;
import org.mule.runtime.extension.api.annotation.param.Connection;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Example;
import org.mule.runtime.extension.api.annotation.param.display.Summary;

import org.mule.runtime.extension.api.annotation.param.display.Text;
import org.mule.runtime.extension.api.runtime.operation.FlowListener;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.streaming.PagingProvider;
import org.mule.runtime.extension.api.runtime.streaming.StreamingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* This class is a container for operations, every public method in this class will be taken as an extension operation for the MarkLogic MuleSoft Connector
* 
* @author Jonathan Krebs (jkrebs)
* @author Clay Redding (wattsferry)
* @author John Shingler (jshingler)
* @since 1.0.0
* @version 1.1.1
* @see <a target="_blank" href="https://github.com/marklogic-community/marklogic-mule-connector">MarkLogic MuleSoft Connector GitHub</a>
* 
*/
public class MarkLogicOperations
{

    private static final Logger logger = LoggerFactory.getLogger(MarkLogicOperations.class);
    private static final String OUTPUT_URI_TEMPLATE = "%s%s%s"; // URI Prefix + basenameUri + URI Suffix

    private ObjectMapper jsonFactory = new ObjectMapper();

 /**
 * <p>Loads JSON, XML, text, or binary document content asynchronously into MarkLogic, via the <a target="_blank" href="https://docs.marklogic.com/guide/java/intro">MarkLogic Java API</a> <a target="_blank" href="https://docs.marklogic.com/guide/java/data-movement">Data Movement SDK (DMSDK)</a> returning the DMSDK <a target="_blank" href="https://docs.marklogic.com/javadoc/client/com/marklogic/client/datamovement/JobTicket.html">JobTicket</a> ID used to insert the contents into MarkLogic.</p>
 * @param configuration The MarkLogic configuration details
 * @param connection The MarkLogic connection details
 * @param docPayloads The content of the input files to be used for ingestion into MarkLogic.
 * @param outputCollections A comma-separated list of output collections used during ingestion.
 * @param outputPermissions A comma-separated list of roles and capabilities used during ingestion.
 * @param outputQuality A number indicating the quality of the persisted documents.
 * @param outputUriPrefix The URI prefix, used to prepend and concatenate basenameUri.
 * @param outputUriSuffix The URI suffix, used to append and concatenate basenameUri.
 * @param generateOutputUriBasename Creates a document basename based on an auto-generated UUID.
 * @param basenameUri File basename to be used for persistence in MarkLogic, usually payload-derived.
 * @param temporalCollection The temporal collection imported documents will be loaded into.
 * @param serverTransform The name of a deployed MarkLogic server-side Javascript, XQuery, or XSLT.
 * @param serverTransformParams A comma-separated list of alternating transform parameter names and values.
 * @return java.lang.String
 * @throws com.marklogic.mule.extension.connector.internal.error.provider.MarkLogicExecuteErrorsProvider
 * @since 1.0.0
 * @version 1.1.1
 */
    @MediaType(value = APPLICATION_JSON, strict = true)
    @Throws(MarkLogicExecuteErrorsProvider.class)
    public String importDocs(
            @Config MarkLogicConfiguration configuration,
            @Connection MarkLogicConnector connection,
            @DisplayName("Document payload")
            @Summary("The content of the input files to be used for ingestion into MarkLogic.")
            @Example("#[payload]")
            @Content InputStream docPayloads,
            @Optional(defaultValue = "null")
            @Summary("A comma-separated list of output collections used during ingestion.")
            @Example("mulesoft-test") String outputCollections,
            @Optional(defaultValue = "rest-reader,read,rest-writer,update")
            @Summary("A comma-separated list of roles and capabilities used during ingestion.")
            @Example("myRole,read,myRole,update") String outputPermissions,
            @Optional(defaultValue = "1")
            @Summary("A number indicating the quality of the persisted documents.")
            @Example("1") int outputQuality,
            @Optional(defaultValue = "/")
            @Summary("The URI prefix, used to prepend and concatenate basenameUri.")
            @Example("/mulesoft/") String outputUriPrefix,
            @Optional(defaultValue = "")
            @Summary("The URI suffix, used to append and concatenate basenameUri.")
            @Example(".json") String outputUriSuffix,
            @DisplayName("Generate output URI basename?")
            @Optional(defaultValue = "true")
            @Summary("Creates a document basename based on an auto-generated UUID.")
            @Example("false") boolean generateOutputUriBasename,
            @DisplayName("Output document basename")
            @Optional(defaultValue = "null")
            @Summary("File basename to be used for persistence in MarkLogic, usually payload-derived.")
            @Example("employee123.json") String basenameUri,
            @DisplayName("Temporal collection")
            @Optional(defaultValue = "null")
            @Summary("The temporal collection imported documents will be loaded into.")
            @Example("myTemporalCollection") String temporalCollection,
            @Summary("The name of a deployed MarkLogic server-side Javascript, XQuery, or XSLT.")
            @Optional(defaultValue = "null")
            @Example("ml:sjsInputFlow") String serverTransform,
            @Summary("A comma-separated list of alternating transform parameter names and values.")
            @Optional(defaultValue = "null")
            @Example("entity-name,MyEntity,flow-name,loadMyEntity")
            String serverTransformParams
            )
    {

        // Get a handle to the Insertion batch manager
        MarkLogicInsertionBatcher batcher = MarkLogicInsertionBatcher.getInstance(configuration, connection, outputCollections, outputPermissions, outputQuality, configuration.getJobName(), temporalCollection,serverTransform,serverTransformParams);

        // Determine output URI
        // If the config tells us to generate a new UUID, do that
        if (generateOutputUriBasename)
        {
            basenameUri = UUID.randomUUID().toString();
            // Also, if the basenameURI is blank for whatever reason, use a new UUID
        }
        else if ((basenameUri == null) || (basenameUri.equals("null")) || (basenameUri.length() < 1))
        {
            basenameUri = UUID.randomUUID().toString();
        }

        // Assemble the output URI components
        String outURI = String.format(OUTPUT_URI_TEMPLATE, outputUriPrefix, basenameUri, outputUriSuffix);

        // Actually do the insert and return the result
        return batcher.doInsert(outURI, docPayloads);
    }

    /*
  Sample JSON created by getJobReport() :
{
	"importResults": [
		{
			"jobID": "59903224-c3db-46d8-9881-d24952131b4d",
			"jobOutcome": "successful",
			"successfulBatches": 2,
			"successfulEvents": 100,
			"failedBatches": 0,
			"failedEvents": 0,
			"jobName": "test-import",
			"jobStartTime": "2019-04-18T12:00:00Z",
			"jobEndTime": "2019-04-18T12:00:01Z",
			"jobReportTime": "2019-04-18T12:00:02Z"
		}
	],
	"exportResults": []
}
     */

 /**
 * <p>Retrieves a JSON representation of a <a target="_blank" href="https://docs.marklogic.com/guide/java/intro">MarkLogic Java API</a> <a target="_blank" href="https://docs.marklogic.com/guide/java/data-movement">Data Movement SDK (DMSDK)</a> <a target="_blank" href="https://docs.marklogic.com/javadoc/client/com/marklogic/client/datamovement/JobReport.html">JobReport</a> following an importDocs operation.</p>
 * @return java.lang.String
 * @throws com.marklogic.mule.extension.connector.internal.error.provider.MarkLogicExecuteErrorsProvider
 * @since 1.0.0
 * @deprecated Deprecated in v.1.1.1
 */
    @MediaType(value = APPLICATION_JSON, strict = true)
    @DisplayName("Get Job Report (deprecated)")
//    @org.mule.runtime.extension.api.annotation.deprecated.Deprecated(message = "This operation should no longer be used.  Instead, use the built-in MuleSoft BatchJobResult output.", since = "1.1.0")
    public String getJobReport()
    {
        ObjectNode rootObj = jsonFactory.createObjectNode();

        ArrayNode exports = jsonFactory.createArrayNode();
        rootObj.set("exportResults", exports);
        MarkLogicInsertionBatcher insertionBatcher = MarkLogicInsertionBatcher.getInstance();
        if (insertionBatcher != null)
        {
            ArrayNode imports = jsonFactory.createArrayNode();
            imports.add(insertionBatcher.createJsonJobReport(jsonFactory));
            rootObj.set("importResults", imports);
        }

        // Add support for query jobReport here!
        String result = rootObj.toString();

        // Add support for query result report here!
        return result;

    }

 /**
 * <p>Echoes the current MarkLogicConnector and MarkLogicConfiguration information.</p>
 * @param configuration The MarkLogic configuration details
 * @param connection The MarkLogic connection details
 * @return java.lang.String
 * @since 1.0.0
 * @version 1.1.1
 */
    @MediaType(value = ANY, strict = false)
    public String retrieveInfo(@Config MarkLogicConfiguration configuration, @Connection MarkLogicConnector connection)
    {
        return "Using Configuration [" + configuration.getConfigId() + "] with Connection id [" + connection.getId() + "]";
    }

 /**
 * <p>Delete query-selected document content asynchronously from MarkLogic, via the <a target="_blank" href="https://docs.marklogic.com/guide/java/intro">MarkLogic Java API</a> <a target="_blank" href="https://docs.marklogic.com/guide/java/data-movement">Data Movement SDK (DMSDK)</a> returning a JSON object detailing the outcome.</p>
 * @param configuration The MarkLogic configuration details
 * @param connection The MarkLogic connection details
 * @param queryString The serialized query XML or JSON.
 * @param optionsName The server-side Search API options file used to configure the search.
 * @param queryStrategy The Java class used to execute the serialized query.
 * @param useConsistentSnapshot Whether to use a consistent point-in-time snapshot for operations.
 * @param fmt The format of the serialized query.
 * @return java.lang.String
 * @throws com.marklogic.mule.extension.connector.internal.error.provider.MarkLogicExecuteErrorsProvider
 * @since 1.1.0
 * @version 1.1.1
 */
    @MediaType(value = APPLICATION_JSON, strict = true)
    @Throws(MarkLogicExecuteErrorsProvider.class)
    public String deleteDocs(
            @Config MarkLogicConfiguration configuration,
            @Connection MarkLogicConnector connection,
            @DisplayName("Serialized Query String")
            @Summary("The serialized query XML or JSON.")
            @Text String queryString,
            @DisplayName("Search API Options")
            @Optional
            @Summary("The server-side Search API options file used to configure the search.") String optionsName,
            @DisplayName("Search Strategy")
            @Summary("The Java class used to execute the serialized query.") MarkLogicQueryStrategy queryStrategy,
            @DisplayName("Use Consistent Snapshot")
            @Summary("Whether to use a consistent point-in-time snapshot for operations.") boolean useConsistentSnapshot,
            @DisplayName("Serialized Query Format")
            @Summary("The format of the serialized query.") MarkLogicQueryFormat fmt
    )
    {
        DatabaseClient client = connection.getClient();
        QueryManager qm = client.newQueryManager();
        DataMovementManager dmm = client.newDataMovementManager();
        QueryDefinition query = queryStrategy.getQueryDefinition(qm,queryString,fmt,optionsName);
        QueryBatcher batcher = queryStrategy.newQueryBatcher(dmm,query);
        SearchHandle resultsHandle = qm.search(query, new SearchHandle());
        
        if (useConsistentSnapshot)
        {
            batcher.withConsistentSnapshot();
        }
        
        batcher.withBatchSize(configuration.getBatchSize())
                .withThreadCount(configuration.getThreadCount())
                .onUrisReady(new DeleteListener())
                .onQueryFailure((throwable) ->
                {
                    logger.error("Exception thrown by an onBatchSuccess listener", throwable);  // For Sonar...
                });
        dmm.startJob(batcher);
        batcher.awaitCompletion();
        dmm.stopJob(batcher);
        
        ObjectNode rootObj = jsonFactory.createObjectNode();
        rootObj.put("deletionResult", String.format("%d document(s) deleted", resultsHandle.getTotalResults()));
        rootObj.put("deletionCount", resultsHandle.getTotalResults());
        
        return rootObj.toString();
    }

 /**
 * <p>Retrieve query-selected document content synchronously from MarkLogic, via the <a target="_blank" href="https://docs.marklogic.com/guide/java/intro">MarkLogic Java API</a>.</p>
 * @param configuration The MarkLogic configuration details
 * @param structuredQuery The serialized query XML or JSON.
 * @param optionsName The server-side Search API options file used to configure the search.
 * @param structuredQueryStrategy The Java class used to execute the serialized query
 * @param fmt The format of the serialized query.
 * @param serverTransform The name of a deployed MarkLogic server-side Javascript, XQuery, or XSLT.
 * @param serverTransformParams A comma-separated list of alternating transform parameter names and values.
 * @param streamingHelper The streaming helper.
 * @param flowListener The flow listener.
 * @return org.mule.runtime.extension.api.runtime.streaming.PagingProvider
 * @throws com.marklogic.mule.extension.connector.internal.error.provider.MarkLogicExecuteErrorsProvider
 * @deprecated Deprecated in v.1.1.0, use queryDocs instead
 * @since 1.1.0
 */
    @MediaType(value = ANY, strict = false)
    @OutputResolver(output = MarkLogicSelectMetadataResolver.class)
    @DisplayName("Select Documents By Structured Query (deprecated)")
    //@org.mule.runtime.extension.api.annotation.deprecated.Deprecated(message = "Use Query Docs instead", since = "1.1.0")
    @Throws(MarkLogicExecuteErrorsProvider.class)
    public PagingProvider<MarkLogicConnector, Object> selectDocsByStructuredQuery(
            @Config MarkLogicConfiguration configuration,
            @DisplayName("Serialized Query String")
            @Summary("The serialized query XML or JSON.")
            @Text String structuredQuery,
            @DisplayName("Search API Options")
            @Optional
            @Summary("The server-side Search API options file used to configure the search.") String optionsName,
            @DisplayName("Search Strategy")
            @Summary("The Java class used to execute the serialized query.") MarkLogicQueryStrategy structuredQueryStrategy,
            @DisplayName("Serialized Query Format")
            @Summary("The format of the serialized query.") MarkLogicQueryFormat fmt,
            @Summary("The name of a deployed MarkLogic server-side Javascript, XQuery, or XSLT.")
            @Optional(defaultValue = "null")
            @Example("ml:sjsInputFlow") String serverTransform,
            @Summary("A comma-separated list of alternating transform parameter names and values.")
            @Optional(defaultValue = "null")
            @Example("entity-name,MyEntity,flow-name,loadMyEntity") String serverTransformParams,
            StreamingHelper streamingHelper,
            FlowListener flowListener
    )
    {
        return queryDocs(configuration, structuredQuery, optionsName, null, null, structuredQueryStrategy, fmt, serverTransform, serverTransformParams, streamingHelper, flowListener);
    }

 /**
 * <p>Retrieve query-selected document content synchronously from MarkLogic, via the <a target="_blank" href="https://docs.marklogic.com/guide/java/intro">MarkLogic Java API</a>.</p>
 * @param configuration The MarkLogic configuration details
 * @param queryString The serialized query XML or JSON.
 * @param optionsName The server-side Search API options file used to configure the search.
 * @param pageLength Number of documents fetched at a time, defaults to the connection batch size.
 * @param maxResults Maximum total number of documents to be fetched, defaults to unlimited.
 * @param queryStrategy The Java class used to execute the serialized query
 * @param fmt The format of the serialized query.
 * @param serverTransform The name of a deployed MarkLogic server-side Javascript, XQuery, or XSLT.
 * @param serverTransformParams A comma-separated list of alternating transform parameter names and values.
 * @param streamingHelper The streaming helper.
 * @param flowListener The flow listener.
 * @return org.mule.runtime.extension.api.runtime.streaming.PagingProvider
 * @throws com.marklogic.mule.extension.connector.internal.error.provider.MarkLogicExecuteErrorsProvider
 * @since 1.1.0
 * @version 1.1.1
 */
    @MediaType(value = ANY, strict = false)
    @OutputResolver(output = MarkLogicSelectMetadataResolver.class)
    @Throws(MarkLogicExecuteErrorsProvider.class)
    public PagingProvider<MarkLogicConnector, Object> queryDocs(
            @Config MarkLogicConfiguration configuration,
            @DisplayName("Serialized Query String")
            @Summary("The serialized query XML or JSON.")
            @Text String queryString,
            @DisplayName("Search API Options")
            @Optional
            @Summary("The server-side Search API options file used to configure the search.") String optionsName,
            @DisplayName("Page Length")
            @Optional
            @Summary("Number of documents fetched at a time, defaults to the connection batch size.") Integer pageLength,
            @DisplayName("Maximum Number of Results")
            @Optional
            @Summary("Maximum total number of documents to be fetched, defaults to unlimited.") Long maxResults,
            @DisplayName("Search Strategy")
            @Summary("The Java class used to execute the serialized query.") MarkLogicQueryStrategy queryStrategy,
            @DisplayName("Serialized Query Format")
            @Summary("The format of the serialized query.") MarkLogicQueryFormat fmt,
            @Summary("The name of a deployed MarkLogic server-side Javascript, XQuery, or XSLT.")
            @Optional(defaultValue = "null")
            @Example("ml:sjsInputFlow") String serverTransform,
            @Summary("A comma-separated list of alternating transform parameter names and values.")
            @Optional(defaultValue = "null")
            @Example("entity-name,MyEntity,flow-name,loadMyEntity") String serverTransformParams,
            StreamingHelper streamingHelper,
            FlowListener flowListener)
    {
        return new PagingProvider<MarkLogicConnector, Object>()
        {

            private final AtomicBoolean initialised = new AtomicBoolean(false);
            private MarkLogicResultSetCloser resultSetCloser;
            MarkLogicResultSetIterator iterator;

            @Override
            public List<Object> getPage(MarkLogicConnector connection)
            {
                if (initialised.compareAndSet(false, true))
                {
                    resultSetCloser = new MarkLogicResultSetCloser(connection);
                    flowListener.onError(ex ->
                    {
                        logger.error(String.format("Exception was thrown during select operation. Error was: %s", ex.getMessage()), ex);
                        try
                        {
                            close(connection);
                        }
                        catch (MuleException e)
                        {
                            logger.info(String.format("Exception was found closing connection for select operation. Error was: %s", e.getMessage()), e);
                        }
                    });

                    DatabaseClient client = connection.getClient();
                    QueryManager qm = client.newQueryManager();

                    String options = isDefined(optionsName) ? optionsName : null;
                    QueryDefinition query = queryStrategy.getQueryDefinition(qm,queryString,fmt,options);

                    if (MarkLogicConfiguration.serverTransformExists(serverTransform))
                    {
                        query.setResponseTransform(MarkLogicConfiguration.createServerTransform(serverTransform,serverTransformParams));
                        logger.info("Transforming query doc payload with operation-defined transform: " + serverTransform);
                    }
                    else if (configuration.hasServerTransform())
                    {
                        query.setResponseTransform(configuration.createServerTransform());
                        logger.info("Transforming query doc payload with connection-defined transform: " + configuration.getServerTransform());
                    }
                    else
                    {
                        logger.info("Querying docs without a transform");
                    }

                    if ((pageLength != null) && (pageLength < 1))
                    {
                        iterator = new MarkLogicResultSetIterator(connection, configuration, query, configuration.getBatchSize(), maxResults);
                    }
                    else
                    {
                        iterator = new MarkLogicResultSetIterator(connection, configuration, query, pageLength, maxResults);
                    }

                }
                return iterator.next();
            }

            @Override
            public java.util.Optional<Integer> getTotalResults(MarkLogicConnector markLogicConnector)
            {
                return java.util.Optional.empty();
            }

            @Override
            public void close(MarkLogicConnector connection) throws MuleException
            {
                resultSetCloser.closeResultSets();
            }

            @Override
            public boolean useStickyConnections()
            {
                return true;
            }
        };
    }

 /**
 * <p>Retrieve query-selected document content asynchronously from MarkLogic, via the <a target="_blank" href="https://docs.marklogic.com/guide/java/intro">MarkLogic Java API</a> <a target="_blank" href="https://docs.marklogic.com/guide/java/data-movement">Data Movement SDK (DMSDK)</a>.</p>
 * @param pageLength Number of documents fetched at a time, defaults to the connection batch size.
 * @param configuration The MarkLogic configuration details
 * @param queryString The serialized query XML or JSON.
 * @param optionsName The server-side Search API options file used to configure the search.
 * @param queryStrategy The Java class used to execute the serialized query.
 * @param maxResults Maximum total number of documents to be fetched, defaults to unlimited.
 * @param useConsistentSnapshot Whether to use a consistent point-in-time snapshot for operations.
 * @param fmt The format of the serialized query.
 * @return org.mule.runtime.extension.api.runtime.streaming.PagingProvider
 * @throws com.marklogic.mule.extension.connector.internal.error.provider.MarkLogicExecuteErrorsProvider
 * @since 1.1.0
 * @version 1.1.1
 */
    @MediaType(value = ANY, strict = false)
    @OutputResolver(output = MarkLogicSelectMetadataResolver.class)
    @Throws(MarkLogicExecuteErrorsProvider.class)
    public PagingProvider<MarkLogicConnector, Result<Object, MarkLogicAttributes>> exportDocs(
            @Config MarkLogicConfiguration configuration,
            @DisplayName("Serialized Query String")
            @Summary("The serialized query XML or JSON.")
            @Text String queryString,
            @DisplayName("Search API Options")
            @Optional
            @Summary("The server-side Search API options file used to configure the search.") String optionsName,
            @DisplayName("Search Strategy")
            @Summary("The Java class used to execute the serialized query.") MarkLogicQueryStrategy queryStrategy,
            @DisplayName("Maximum Number of Results")
            @Optional
            @Summary("Maximum total number of documents to be fetched, defaults to unlimited.") Long maxResults,
            @DisplayName("Use Consistent Snapshot")
            @Summary("Whether to use a consistent point-in-time snapshot for operations.") boolean useConsistentSnapshot,
            @DisplayName("Serialized Query Format")
            @Summary("The format of the serialized query.") MarkLogicQueryFormat fmt,
            StreamingHelper streamingHelper
    )
    {
        maxResults = maxResults != null ? maxResults : 0;
        MarkLogicExportListener exportListener = new MarkLogicExportListener(maxResults);

        return new PagingProvider<MarkLogicConnector, Result<Object, MarkLogicAttributes>>()
        {

            private final AtomicBoolean initialised = new AtomicBoolean(false);
            private MarkLogicResultSetCloser resultSetCloser;
            private QueryBatcher batcher;
            private DataMovementManager dmm = null;

            @Override
            public List<Result<Object, MarkLogicAttributes>> getPage(MarkLogicConnector markLogicConnector)
            {
                if (initialised.compareAndSet(false, true))
                {
                    DatabaseClient client = markLogicConnector.getClient();
                    QueryManager qm = client.newQueryManager();
                    dmm = client.newDataMovementManager();

                    QueryDefinition query = queryStrategy.getQueryDefinition(qm,queryString,fmt,optionsName);
                    batcher = queryStrategy.newQueryBatcher(dmm,query);

                    if (configuration.hasServerTransform())
                    {
                        query.setResponseTransform(configuration.createServerTransform());
                    }

                    if (useConsistentSnapshot)
                    {
                        batcher.withConsistentSnapshot();
                    }
                    batcher.withBatchSize(configuration.getBatchSize())
                            .withThreadCount(configuration.getThreadCount())
                            .onUrisReady(exportListener)
                            .onQueryFailure((throwable) ->
                            {
                                logger.error("Exception thrown by an onBatchSuccess listener", throwable);  // For Sonar...
                            });
                    dmm.startJob(batcher);
                    batcher.awaitCompletion();
                    dmm.stopJob(batcher);
                }

                if (dmm == null)
                {
                    logger.warn("Data Movement Manager is null after initialization.");
                }
                List<Result<Object, MarkLogicAttributes>> documents = new ArrayList<>();
                for (Result document : exportListener.getDocs()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("exported doc getOutput(): " + document.getOutput());
                        logger.debug("exported doc getMediaType(): " + document.getMediaType());
                    }
                    documents.add(document);
                }

                exportListener.clearDocs();

                return documents;
            }

            @Override
            public java.util.Optional<Integer> getTotalResults(MarkLogicConnector markLogicConnector)
            {
                return java.util.Optional.empty();
            }

            @Override
            public void close(MarkLogicConnector markLogicConnector) throws MuleException
            {
                logger.debug("NOT Invalidating ML connection...");
                //markLogicConnection.invalidate();
            }
        };

    }
    private boolean isDefined(String str)
    {
        return str != null && !str.trim().isEmpty() && !"null".equals(str.trim());
    }
}

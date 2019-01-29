package com.barretta.elastic.adsb

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.elastic.barretta.clients.ESClient
import groovy.util.logging.Slf4j

@Slf4j
class ADSBCollector implements RequestHandler<String, String> {
    private def properties

    String handleRequest(String request, Context context) {
        loadProperties()
        doIt()
    }

    def doIt() {
        loadProperties()
        log.info("fetching all states")
        def allStates = OpenSkyNetworkClient.getAllStates()
        log.info(" ...found [${allStates.states.size()}]")

        def client = new ESClient(properties.esClient as ESClient.Config)
        client.bulk([(ESClient.BulkOps.INSERT): allStates.states.collect { it.properties.remove("class"); it.properties }])
        log.info("done")
    }

    private def loadProperties() {
        properties = new ConfigSlurper().parse(GroovyClassLoader.getSystemResource("properties.groovy"))
    }
}

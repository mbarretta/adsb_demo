package com.barretta.elastic.adsb

import com.elastic.barretta.clients.ESClient
import groovy.cli.commons.CliBuilder
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsExecutorsPool
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.get.GetIndexRequest
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.common.unit.ByteSizeUnit
import org.elasticsearch.common.unit.ByteSizeValue
import org.elasticsearch.index.query.MatchAllQueryBuilder

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

@Slf4j
class ADSBCollector {

    static def aircraft = [:] as ConcurrentHashMap

    //todo add setup option that creates the index templates, rollover, etc., plus the aircraft index from the csv
    static void main(String[] args) {
        def cli = new CliBuilder(usage: "adsb_demo")
        cli.loopInterval(args: 1, argName: "seconds", "seconds between loops")
        cli.help("print this message")
        def options = cli.parse(args)

        if (options.help) {
            cli.usage()
            System.exit(0)
        }

        def interval = options.loopInterval ? Long.valueOf(options.loopInterval) : 5l
        loopIt(interval)
    }

    static void loopIt(long interval) {
        log.info("Let's do this! ...every [$interval] seconds\n")

        log.info("caching all aircraft data")
        getAllAircraft()

        //setup a recurring task to hit the rollover api once per minute
        if (PropertyManager.instance.properties.rollover.enabled == true) {
            def rolloverTask = new TimerTask() {
                @Override
                void run() {
                    rollover()
                }
            }
            def rolloverTimer = new Timer()
            rolloverTimer.scheduleAtFixedRate(rolloverTask, 0, 60000)
        }

        //we're doing this instead of something like Executor.scheduleAtFixRate because we want these tasks to overlap
        //and the Executor says:
        // " If any execution of this task takes longer than its period, then subsequent executions may start late,
        //   but will not concurrently execute."
        //wtf?
        def threads = PropertyManager.instance.properties.maxThreads ?: Runtime.getRuntime().availableProcessors() - 1
        GParsExecutorsPool.withPool(threads) { ExecutorService service ->
            while (true) {
                service.execute(new Collector())
                log.trace("sleeping [$interval] seconds...")
                Thread.sleep(interval * 1000)
                log.trace("...done")
            }
        }
    }

    static class Collector implements Runnable {
        @Override
        void run() {
            def esClient = getEsClient()
            try {
                log.trace("**BEGIN**")
                log.trace("fetching all states")
                def allStates = OpenSkyNetworkClient.getAllStates()
                log.trace(" ...found [${allStates.states.size()}]")

                log.trace("fetching all flights")
                def allFlights = OpenSkyNetworkClient.getAllFlights(allStates.time)
                log.trace(" ...found [${allFlights.flights.size()}]")

                log.trace("joining everything together")
                def esRecords = allStates.states.inject([]) { list, state ->

                    /*
                    we're doing some field name transformation here as well so that state data has a "state." prefix,
                    flight data has "flight.", etc...
                    */

                    def record = [icao: state.icao]

                    //add state data
                    def stateFields = state.properties.collectEntries { [("state.${it.key}".toString()): it.value] }
                    stateFields.remove("state.icao")
                    stateFields.remove("state.class")
                    record += stateFields

                    //join flight data
                    def flight = allFlights.flights.find { it.icao == state.icao }
                    if (flight) {
                        def flightFields = flight.properties.collectEntries {
                            [("flight.${it.key}".toString()): it.value]
                        }
                        flightFields.remove("flight.icao")
                        flightFields.remove("flight.class")
                        record += flightFields
                    }

                    //join aircraft data
                    if (aircraft.containsKey(state.icao)) {
                        record += aircraft.get(state.icao)
                    }

                    list << record
                }
                log.trace(" ...done")

                log.trace("bulking into ES")

                esClient.config.index = PropertyManager.instance.properties.indices.opensky
                esClient.bulk([(ESClient.BulkOps.INSERT): esRecords])
                esClient.close()
                log.trace("**END**")
                log.info("Collected { states: [${allStates.states.size()}], flights: [${allFlights.flights.size()}] }")
            } catch (e) {
                log.error("piss", e)
            } finally {
                esClient.close()
            }
        }
    }

    static def rollover() {
        def props = PropertyManager.instance.properties

        RolloverRequest request = new RolloverRequest(props.indices.opensky, null)
        request.addMaxIndexDocsCondition(props.rollover.max_docs)
        request.addMaxIndexSizeCondition(new ByteSizeValue(props.rollover.max_size_gb, ByteSizeUnit.GB))

        def esClient = getEsClient()
        try {
            def response = esClient.indices().rollover(request, RequestOptions.DEFAULT)
            if (response.isRolledOver()) {
                log.info("Rollover complete: [$response.oldIndex] --> [$response.newIndex]")

                //if we rolled over, we can drop an older one, if so configured
                if (props.rollover.delete_older_than) {
                    def newIndexIndex = (response.newIndex =~ /.*-(\d+)/)[0][1]
                    def oldIndexIndex = ((newIndexIndex as int) - props.rollover.delete_older_than) as String
                    def oldIndex = "${props.indices.opensky}-${oldIndexIndex.padLeft(6, "0")}"
                    if (esClient.indices().exists(new GetIndexRequest().indices(oldIndex), RequestOptions.DEFAULT)) {
                        esClient.delete(new DeleteRequest(oldIndex), RequestOptions.DEFAULT)
                        log.info("deleted old index [$oldIndex]")
                    }
                }
            } else {
                log.debug("No rollover needed: $response.conditionStatus")
            }
        } catch (e) {
            log.error("nuts", e)
        } finally {
            esClient.close()
        }
    }

    static def getAllAircraft() {
        if (aircraft.isEmpty()) {
            def esClient = getEsClient()
            esClient.config.index = PropertyManager.instance.properties.indices.aircraft

            esClient.scrollQuery(new MatchAllQueryBuilder(), 5000, 2, 1) { it ->
                def map = it.getSourceAsMap()
                if (map) {
                    aircraft.put(map.remove("icao"), map.collectEntries {
                        [("aircraft.${it.key}".toString()): it.value]
                    })
                }
            }
            esClient.close()
        }
        return aircraft
    }

    static def getEsClient() {
        return new ESClient(PropertyManager.instance.properties.es as ESClient.Config)
    }
}

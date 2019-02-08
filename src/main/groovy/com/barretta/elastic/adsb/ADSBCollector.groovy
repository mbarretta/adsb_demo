package com.barretta.elastic.adsb

import com.elastic.barretta.clients.ESClient
import groovy.cli.commons.CliBuilder
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsExecutorsPool
import org.elasticsearch.index.query.MatchAllQueryBuilder

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Slf4j
class ADSBCollector {

    static def aircraft = [:] as ConcurrentHashMap
    static volatile ESClient esClient = null

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

        GParsExecutorsPool.withPool(Runtime.getRuntime().availableProcessors() - 1) {
            while (true) {
                it.execute(new Collector())
                log.trace("sleeping [$interval] seconds...")
                Thread.sleep(interval * 1000)
                log.trace("...done")
            }
        }
    }

    static class Collector implements Runnable {
        @Override
        void run() {
//            log.info("sleeping")
//            Thread.sleep(10000)
            log.info("loading all aircraft data")
            def aircraft = getAllAircraft()

            log.info("fetching all states")
            def allStates = OpenSkyNetworkClient.getAllStates()
            log.info(" ...found [${allStates.states.size()}]")

            log.info("fetching all flights")
            def allFlights = OpenSkyNetworkClient.getAllFlights(allStates.time)
            log.info(" ...found [${allFlights.flights.size()}]")

            log.info("joining everything together")
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
                    def flightFields = flight.properties.collectEntries { [("flight.${it.key}".toString()): it.value] }
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
            log.info(" ...done")

            log.info("bulking into ES")
            def esClient = getEsClient()
            esClient.config.index = PropertyManager.instance.properties.indices.opensky
            esClient.bulk([(ESClient.BulkOps.INSERT): esRecords])
            esClient.close()
            log.info(" ...done")
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
//        if (!esClient) {
            def config = PropertyManager.instance.properties.es as ESClient.Config
            def esClient2 = new ESClient(config)
//        }
        return esClient2
    }
}

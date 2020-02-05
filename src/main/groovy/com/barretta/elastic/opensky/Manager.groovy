package com.barretta.elastic.opensky

import com.barretta.elastic.clients.ESClient
import groovy.cli.commons.CliBuilder
import groovy.util.logging.Slf4j
import org.elasticsearch.index.query.MatchAllQueryBuilder

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Slf4j
class Manager {

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
        log.info("Let's do this! ...every [$interval] seconds")

        log.info("caching all aircraft data")
        getAllAircraft()

        //we're doing this instead of something like Executor.scheduleAtFixRate because we want these tasks to overlap
        //and the Executor says:
        // " If any execution of this task takes longer than its period, then subsequent executions may start late,
        //   but will not concurrently execute."
        //wtf?
//        def threads = PropertyManager.instance.properties.maxThreads ?: Runtime.getRuntime().availableProcessors() - 1
//        GParsExecutorsPool.withPool(threads) { ExecutorService service ->
//            while (true) {
//                service.execute(new CollectorRunnable())
//                log.trace("sleeping [$interval] seconds...")
//                Thread.sleep(interval * 1000)
//                log.trace("...done")
//            }
//        }
        def executor = Executors.newSingleThreadScheduledExecutor()
        def client = getEsClient()
        executor.scheduleAtFixedRate(new CollectorRunnable(client), 0, 10, TimeUnit.SECONDS)
    }

    static def getAllAircraft() {
        if (aircraft.isEmpty()) {
            def esClient = getEsClient()
            esClient.config.index = PropertyManager.instance.properties.indices.aircraft

            esClient.scrollQuery(new MatchAllQueryBuilder(), 5000, 2, 1) { it ->
                def map = it.getSourceAsMap()
                if (map) {
                    aircraft.put(map.remove("icao"), [aircraft: map])
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

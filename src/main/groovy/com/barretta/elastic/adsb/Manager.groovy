package com.barretta.elastic.adsb

import com.elastic.barretta.clients.ESClient
import groovy.cli.commons.CliBuilder
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsExecutorsPool
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.get.GetIndexRequest
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.common.unit.ByteSizeUnit
import org.elasticsearch.common.unit.ByteSizeValue
import org.elasticsearch.index.query.MatchAllQueryBuilder

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

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
        log.info("Let's do this! ...every [$interval] seconds\n")

        log.info("caching all aircraft data")
        getAllAircraft()

        //setup a recurring task to hit the rollover api once per minute
        if (PropertyManager.instance.properties.rollover.enabled == true) {
            def rolloverTask = new TimerTask() {
                @Override
                void run() {
                    rollover(PropertyManager.instance.properties.indices.opensky)
                    rollover(PropertyManager.instance.properties.indices.flight_tracks)
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
                service.execute(new CollectorRunnable())
                log.trace("sleeping [$interval] seconds...")
                Thread.sleep(interval * 1000)
                log.trace("...done")
            }
        }
    }

    static def rollover(index) {
        def props = PropertyManager.instance.properties

        RolloverRequest request = new RolloverRequest(index, null)
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
                    def oldIndex = "${index}-${oldIndexIndex.padLeft(6, "0")}"
                    if (esClient.indices().exists(new GetIndexRequest().indices(oldIndex), RequestOptions.DEFAULT)) {
                        esClient.indices().delete(new DeleteIndexRequest(oldIndex), RequestOptions.DEFAULT)
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
                    this.aircraft.put(map.remove("icao"), [ aircraft: map ] )
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

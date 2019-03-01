import com.elastic.barretta.clients.ESClient
import groovy.json.JsonSlurper

ESClient es = new ESClient(
    [
        url  : "",
        user : "elastic",
        pass : "",
        index: "world-atlas"
    ] as ESClient.Config
)

//atlas = new JsonSlurper().parseFile(new File("/workspace/datasets/world-atlas-50m.json"), "UTF-8")

//println "doing countries"
//atlas.objects.countries.geometries.each {
//    def map = it + [type: "countries"]
//    es.index(map)
//}
//println "doing land"
//atlas.objects.land.geometries.each {
//    def map = it + [type: "land"]
//    es.index(map)
//}

println "doing states and cities"
new File("/workspace/datasets/us-states-cities-geo").traverse(type: groovy.io.FileType.FILES) {
    es.index(new JsonSlurper().parseFile(it, "UTF-8"), "us-states-cities")
}

System.exit(0)
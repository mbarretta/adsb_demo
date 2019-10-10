# ADS-B Demo

This code will collect ADS-B data from [Opensky Network](https://opensky-network.org/), do some transforms and joins, and load everything into Elasticsearch.

To build a runnable distro:
```
% ./gradlew clean installDist
```

To run your runnable distro:
```
% cd ./build/install/adsb_demo
% bin/adsb_demo
```

Properties for your ES cluster and (optional) opensky API credentials are set in `src/main/resources/properties.groovy`; see `properties.grooovy.example` for an example...

Oh, and before you run this, you'll need to setup the indices in Elasticsearch. The quickest way is to copy and paste the contents of `src/main/resources/devtools_script.txt` into Dev Tools in Kibana. That will:
- Create ILM policies for the flights and flight tracks indices  
- Create an index template for the flight and flight track data
- Create the initial index for both the flight and flight track data

You'll also need to load in the aircraft database that is joined with the "state" data from Opensky. The easiest way to do that is to use the file upload tool hidden under the [Data Visualizer](https://www.elastic.co/guide/en/kibana/current/xpack-ml.html#xpack-ml) tab of the Machine Learning UI and specify the mapping found in `src/main/resources/aircraft_mapping.json` 

So, overall, you want to:
1. Get an ES cluster w/ Kibana
2. Run the dev tools script
3. Load in the aircraft data 
4. Build this thing
5. Run this thing

...there could be more, but that looks right from here!

Last, the flight tracks thing is pretty wonky. It kind of works, but will be going away once the [geoline aggregation](https://github.com/elastic/elasticsearch/issues/41649) is released. 

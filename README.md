# Kiara DB Demo Project

Spring Boot demo for [Kiara](https://github.com/kapoorlabs/kiara). To try Kiara without installing anything, the
[browser demo](https://kapoor-labs.github.io/kiara-demo/) runs the TypeScript version ([kiara-ts](https://github.com/kapoorlabs/kiara-ts)) on 10,000 products.

## Java Kiara vs Apache Lucene benchmark

See the [benchmark report](docs/lucene-comparison.md) for measured feature coverage,
query latency, index build time, retained heap, methodology and limitations.
The demo and [standalone benchmark project](benchmarks/pom.xml) use Kiara 2.0.16.
The demo targets Java 8; the benchmark uses Lucene 10.5.2 and requires JDK 21+.
The checked-in report and raw results were regenerated with published Kiara
2.0.16 and Lucene 10.5.2. All 30 workers built successfully, including both
schedule sizes that previously failed with 2.0.15: 738 scenario/fork measurements
and 37,110 raw latency samples were recorded across the full matrix.
The default workload matrix matches the TypeScript report's five dataset entries:
real retail (3,788 products), synthetic catalogs (10,000 and 100,000 products),
and synthetic schedules (10,000 and 100,000 events), each with distinct queries.
It covers all 11 Kiara query operators and all 17 indexing annotations, with
independent result-set checks before timing. Raw measurements are retained in
[benchmarks/lucene-comparison.json](benchmarks/lucene-comparison.json).
Decimal-precision and keyword-result differences are documented in the report
and excluded from equivalent-query speed ratios. Text precision and hard-filter
checks are reported separately rather than assuming both engines return the same
records.
Dataset indexing failures are also recorded explicitly; blocked queries have no
fabricated latency or memory measurements, and the other engine still runs.

From the repository root in PowerShell, with `JAVA_HOME` pointing to JDK 21+:

```powershell
.\mvnw.cmd -f benchmarks\pom.xml clean verify
& "$env:JAVA_HOME\bin\java.exe" -jar benchmarks\target\kiara-lucene-benchmark-1.0-SNAPSHOT.jar
```

This regenerates the report and raw data using three isolated JVM runs per engine
for each of the five datasets (30 workers). See the report for smaller smoke-run
options and dataset selection. Full-result materialization can make the complete run lengthy.

## Kiara vs VoltDB Community Edition benchmark

The [execution-only comparison](docs/voltdb-query-execution.md) measures Kiara's
search API and server-side precompiled VoltDB procedures, excluding networking
and client decoding. Both run in Linux containers. SQL/index tuning, execution
plans, correctness checks and fresh measurement trials are retained in the
[execution evidence](benchmarks/voltdb-query-execution.json). Result creation is
included, with different Java-map/VoltTable representations; this is not a pure
index-traversal measurement or a claim about current commercial VoltDB.

The [VoltDB findings and detailed interpretation](docs/voltdb-comparison.md)
explain the mixed execution-only results, actual plans, statistical uncertainty,
and publication limits. Neither engine is uniformly faster.
The [historical end-to-end comparison](docs/voltdb-end-to-end.md) and its
[raw results](benchmarks/voltdb-comparison.json) remain separate: those timings
include local Docker client/server communication and must not be mixed with the
execution-only numbers.

See the [local Docker setup and reproduction instructions](benchmarks/voltdb/README.md).

The execution harness also supports a separate generic-index VoltDB baseline:
`-Profile generic` retains generic indexes, removes workload-specific indexes,
and uses fixed SQL without timing-selected alternatives. The original five
fixtures and both wide-filter fixtures can be run together. Its report and raw
evidence are generated separately only after successful collection and verification;
native query timeouts are recorded without fabricated latencies or symmetric
deadline-win claims.
The legacy server is isolated on loopback and intended only for this experiment.

## Kiara vs SQLite in-memory benchmark

The [SQLite comparison](docs/sqlite-comparison.md) uses the same five fixtures,
fresh trials for both engines, prepared SQLite statements, covering/compound
indexes, full ANALYZE, frozen query plans, and independent correctness checks.
Both engines eagerly produce the same Java map shapes. SQLite JDBC/JNI and
cursor costs are included, so this is embedded API execution rather than pure
native SQL time. It does not measure file-backed reads or transactional throughput.

See [reproduction and limitations](benchmarks/sqlite/README.md) and
[raw SQLite evidence](benchmarks/sqlite-comparison.json).

The [separate generic-index SQLite comparison](docs/sqlite-untuned-comparison.md)
retains basic indexes but omits workload-specific compound indexes, SQL alternative
selection and explicit ANALYZE. It uses fresh measurements for both engines and
keeps the [tuned report's disclosure](docs/sqlite-comparison.md#tuning-disclosure-what-was-done-and-what-it-cost)
and results intact. See [generic raw evidence](benchmarks/sqlite-untuned-comparison.json).

## Wide-filter experiment

The [separate wide-filter harness](benchmarks/wide/README.md) tests 2, 4, 8, 12
and 16 distinct filtered attributes, mixed CONTAINS_EITHER/CONTAINS_ALL operators,
and three expected selectivities against Lucene, tuned VoltDB CE, tuned SQLite
and generic-index SQLite. Historical reports are not overwritten. See the
methodology for timing-boundary differences and publication limitations.


This java based springboot demo project will be of great help, to first time users of Kiara DB, in order for them, to get **started straight away with hands-on experience in building search queries for several use cases, using a sample built-in imdb movie database**. 
Once you get comfortable and realise the value that Kiara DB provides, by giving freedom to search by anything, at high performance, without any composite indexes, you may then dig in for more advanced concepts and details.

This step by step readme doc will guide you, as to how this demo project should be used.
Additionally if you prefer watching a tutorial video, rather than reading the doc, you may watch the tutorial video at this link:-

#### Complete video walkthrough: -
https://www.youtube.com/watch?v=b-IqstIuUDY&t=593s

#### How Kiara Stands out: -
https://www.youtube.com/watch?v=-AL6TrOgdKI

#### Kiara query walkthrough: -
https://www.youtube.com/watch?v=-T-Bwu0WQAI


#### Prerequisites: -
Following third party packages must be installed on your system
```
- Java runtime 1.8 and above
- Maven
- Git
```
Kiara uses Lombok, if you are using an IDE that does not have a lombok plugin installed, the IDE might report you errors, but the code will just build fine.
https://projectlombok.org

#### Step 1: Clone the git hub repo.
```sh
$ git clone https://github.com/kapoorlabs/kiara-demo.git
```

#### Step 2: Go to your downloaded repo and compile project.
```sh
$ cd kiara-demo/
$ mvn clean install
```

#### Step 3: Start your application.
From the root folder of the project

```sh
$ java -jar target/kiara-demo-0.0.1-SNAPSHOT.jar
```


  - When the project is started, you would see a message "Started KiaraDemoApplication" on your command screen.
  - Demo project uses port 8080, so if some app on your machine is already using that port, you will have to terminate that app, to free up the port.

#### Step 4: Enjoy querying!
The movie database you will be querying is loaded from a csv file, which can be found at: -
https://github.com/kapoorlabs/kiara-demo/blob/master/src/main/resources/movie_metadata.csv
**if you make any changes to the csv file in your local repo, repeat Steps 2 and 3, which will load the new updated dataset.**

**For the simplicity of this demo we have chosen to demonstrate the queries through a REST API interface, but keep in mind that KIARA DB is a Java library, which can be used in any Java project and not just a REST API.**

You can use a tool like postman to Submit queries as a POST request.

The URL of your application will be: -

```sh
http://localhost:8080/movie.html
```

The URL for Airport type ahead demo would be

```sh
http://localhost:8080/airport.html
```

The URL for Movie keyword demo would be

```sh
http://localhost:8080/keyword.html
```

##### Request Structure

The request has only 2 following Json fields: -
 - **filterSet** - this is an optional field, which is an array of string values, representing the data attributes you would want in your result.
 - **conditions** - This is an array of conditions or the search criteria, using which you can build KIARA DB's queries. A condition has following 3 parts/fields : -
    -   fieldName - The data attribute on which the condition is applied.
    -   Operator - Kiara SDK has some rich set of operators, which forms the search critera.
     You can get complete list and details about operators at: https://www.kapoorlabs.com/kiara-deep-dive/#contentSimpleOperations
    -   Values - the list of String values, the operator will operate on.

Below are some examples that will solidify your understanding:-

###### Example: 1 We will start with a heavy example and then go lighter
Search criteria: 
-   **Movies of either Tom Hanks or Nick Searcy**
-   **Which is a COLOR film.**
-   **Whose aspect ratio is 1.85**
-   **Released in USA**
-   **With duration greater than 120 minutes.**
-   **Number of faces in poster were either 1 or 2**
-   **Budget was greater than 50 Million**
-   **Which was not directed by Penny Marshall**
-   **With content rating not equal to R**
-   **Belonging to either Drama or Action genres**
-   **With IMDB score greater than 7**
-   **With number of critic reviews greater than 200**
-   **Released between 2000-2005**
-   **Plot involves christmas and survival**
-   

And.... the answer is ***Cast Away***
    
```sh
{
	"filterSet": ["movie_title"],
	"conditions": [
		{
			"fieldName" : "ACTORS",
			"operator" : "CONTAINS_EITHER",
			"values" : [
				"Tom Hanks",
				"Nick Searcy"
			]			
		},
		{
			"fieldName" : "COLOR",
			"operator" : "EQUAL",
			"values" : ["Color"]			
		},
		{
			"fieldName" : "ASPECT_RATIO",
			"operator" : "EQUAL",
			"values" : ["1.85"]			
		},
		{
			"fieldName" : "COUNTRY",
			"operator" : "EQUAL",
			"values" : ["USA"]			
		},
		{
			"fieldName" : "DURATION",
			"operator" : "GREATER_THAN",
			"values" : ["120"]			
		},
		{
			"fieldName" : "FACE_NUMBER_IN_POSTER",
			"operator" : "EQUAL",
			"values" : [
				"2",
				"1"
			]			
		},
		{
			"fieldName" : "BUDGET",
			"operator" : "GREATER_THAN",
			"values" : ["50000000"]			
		},
		{
			"fieldName" : "DIRECTOR_NAME",
			"operator" : "NOT_EQUAL",
			"values" : ["Penny Marshall"]			
		},
		{
			"fieldName" : "CONTENT_RATING",
			"operator" : "NOT_EQUAL",
			"values" : ["R"]			
		},
		{
			"fieldName" : "GENRES",
			"operator" : "CONTAINS_EITHER",
			"values" : [
				"Drama",
				"Action"
			]			
		},
		{
			"fieldName" : "IMDB_SCORE",
			"operator" : "GREATER_THAN_EQUAL",
			"values" : ["7"]			
		},
		{
			"fieldName" : "NUM_CRITIC_FOR_REVIEWS",
			"operator" : "GREATER_THAN",
			"values" : ["200"]	
		},
		{
			"fieldName" : "TITLE_YEAR",
			"operator" : "BETWEEN",
			"lowerValue" : "2000",
			"upperValue" : "2005"
		},
		{
			"fieldName" : "PLOT_KEYWORDS",
			"operator" : "CONTAINS_ALL",
			"values" : [
				"christmas",
				"survival"
			]
		}
	]
}
```
.
 <img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo10.png"/>

###### Example: 2
Search criteria: **Movies that have ***BOTH*** Kate Winslet and Leonardo DiCaprio, with selected attributes.**

```
{
	"filterSet": ["movie_title", "duration", "plot_keywords","actors"],
	"conditions": [
		{
			"fieldName" : "actors",
			"operator" : "CONTAINS_ALL",
			"values" : ["Kate Winslet", "Leonardo DiCaprio"]			
		}
	]
}
```
.

<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo1.png"/>

###### Example: 3
Search criteria: **Movies that have ***BOTH*** Kate Winslet and Leonardo DiCaprio, with ALL attributes.**

```
{
	"conditions": [
		{
			"fieldName" : "actors",
			"operator" : "CONTAINS_ALL",
			"values" : ["Kate Winslet", "Leonardo DiCaprio"]			
		}
		
	]
}
```
.
<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo2.png"/>

###### Example: 4
Search criteria: **Movies that contains ***EITHER*** had Kate Winslet OR Leonardo DiCaprio, with selected attributes.**

```
{
	"filterSet": ["movie_title", "duration", "plot_keywords","actors"],
	"conditions": [
		{
			"fieldName" : "actors",
			"operator" : "CONTAINS_EITHER",
			"values" : ["Kate Winslet", "Leonardo DiCaprio"]			
		}
		
	]
}
```
.
<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo3.png"/>

###### Example: 5
Search criteria: **Movies of Leonardo DiCaprio that he didn't do with Kate Winslet, with selected attributes.**

```
{
	"filterSet": ["movie_title", "duration", "plot_keywords","actors"],
	"conditions": [
		{
			"fieldName" : "actors",
			"operator" : "CONTAINS_EITHER",
			"values" : ["Leonardo DiCaprio"]			
		},
		{
			"fieldName" : "actors",
			"operator" : "NOT_CONTAINS",
			"values" : ["Kate Winslet"]			
		}
		
	]
}
```
.
<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo4.png"/>


###### Example: 6
Search criteria: **Movies of Leonardo DiCaprio that he didn't do with either Kate Winslet or Christoph Waltz.**

```
{
	"filterSet": ["movie_title", "duration", "plot_keywords","actors"],
	"conditions": [
		{
			"fieldName" : "actors",
			"operator" : "CONTAINS_EITHER",
			"values" : ["Leonardo DiCaprio"]			
		},
		{
			"fieldName" : "actors",
			"operator" : "NOT_CONTAINS",
			"values" : ["Kate Winslet", "Christoph Waltz"]			
		}
		
	]
}
```
.
<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo5.png"/>


###### Example: 7

Search criteria: **Movies of Dwayne Johnson that came after 2011.**

```
{
	"filterSet": ["movie_title", "plot_keywords","actors", "title_year"],
	"conditions": [
		{
			"fieldName" : "actors",
			"operator" : "EQUAL",
			"values" : ["Dwayne Johnson"]			
		},
		
		{
			"fieldName" : "title_year",
			"operator" : "GREATER_THAN",
			"values" : ["2011"]			
		}
		
	]
}
```
.
<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo6.png"/>

###### Example: 8
Search criteria: ***Comedy*** **Movies of Dwayne Johnson that came after 2011**

```
{
	"filterSet": ["movie_title", "plot_keywords","actors", "title_year","genres"],
	"conditions": [
		{
			"fieldName" : "actors",
			"operator" : "EQUAL",
			"values" : ["Dwayne Johnson"]			
		},
		
		{
			"fieldName" : "title_year",
			"operator" : "GREATER_THAN",
			"values" : ["2011"]			
		} ,
		
		{
			"fieldName" : "genres",
			"operator" : "CONTAINS_EITHER",
			"values" : ["Comedy"]			
		}
		
	]
}

```
.
<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo7.png"/>

###### Example: 9

Search criteria: **Either Comedy  or Fantasy Movies of Dwayne Johnson that came after 2011**

```
{
	"filterSet": ["movie_title", "plot_keywords","actors", "title_year","genres"],
	"conditions": [
		{
			"fieldName" : "actors",
			"operator" : "EQUAL",
			"values" : ["Dwayne Johnson"]			
		},
		
		{
			"fieldName" : "title_year",
			"operator" : "GREATER_THAN",
			"values" : ["2011"]			
		} ,
		
		{
			"fieldName" : "genres",
			"operator" : "CONTAINS_EITHER",
			"values" : ["Comedy", "Fantasy"]			
		}
		
	]
}

```
.
<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo8.png"/>

###### Example: 10
Search criteria: **Comedy PLUS Fantasy Movies of Dwayne Johnson that came after 2011**

```
{
	"filterSet": ["movie_title", "plot_keywords","actors", "title_year","genres"],
	"conditions": [
		{
			"fieldName" : "actors",
			"operator" : "EQUAL",
			"values" : ["Dwayne Johnson"]			
		},
		
		{
			"fieldName" : "title_year",
			"operator" : "GREATER_THAN",
			"values" : ["2011"]			
		} ,
		
		{
			"fieldName" : "genres",
			"operator" : "CONTAINS_ALL",
			"values" : ["Comedy", "Fantasy"]			
		}
		
	]
}

```
.
<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/07/demo9.png"/>


Visit www.kapoorlabs.com/kiara to get latest info

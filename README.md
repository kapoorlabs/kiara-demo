# Kiara DB Demo Project

<img src="https://www.kapoorlabs.com/wp-content/uploads/2020/06/54624690-AA61-4E27-8305-9234C6861A86-e1593536390892.png"/>

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

#### Step 1: Clone the git hub repo.
```sh
$ git clone https://github.com/kapoorlabs/kiara-demo.git
```

#### Step 2: Go to you downloaded repo and compile project.
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
**if you make any changes to the csv file in your local repo, repeate Steps 2 and 3, which will load the new updated dataset.**

**For the simplicity of this demo we have chosen to demonstrate the queries through a REST API interface, but keep in mind that KIARA DB is a Java library, which can be used in any Java project and not just a REST API.**

You can use a tool like postman to Submit queries as a POST request.

The URL of your application will be: -

```sh
http://localhost:8080/movie
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

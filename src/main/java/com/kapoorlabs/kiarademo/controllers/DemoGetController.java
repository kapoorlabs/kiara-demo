package com.kapoorlabs.kiarademo.controllers;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class DemoGetController {
    @GetMapping(value = "/movie", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String welcomeAsHTML() {
        return "<!doctype html>\n" + 
        		"<html lang=\"en\">\n" + 
        		"  <head>\n" + 
        		"    <!-- Required meta tags -->\n" + 
        		"    <meta charset=\"utf-8\">\n" + 
        		"    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, shrink-to-fit=no\">\n" + 
        		"\n" + 
        		"    <!-- Bootstrap CSS -->\n" + 
        		"    <link rel=\"stylesheet\" href=\"https://stackpath.bootstrapcdn.com/bootstrap/4.5.0/css/bootstrap.min.css\" integrity=\"sha384-9aIt2nRpC12Uk9gS9baDl411NQApFmC26EwAOH8WgZl5MYYxFfc+NcPb1dKGj7Sk\" crossorigin=\"anonymous\">\n" + 
        		"\n" + 
        		"    <title>Kiara Demo Project!</title>\n" + 
        		"    <script src=\"https://ajax.googleapis.com/ajax/libs/jquery/3.5.1/jquery.min.js\"></script>\n" + 
        		"    <script>\n" + 
        		"        function sendQuery() {\n" + 
        		"\n" + 
        		"            var request = $(\"textarea#query\").val();\n" + 
        		"\n" + 
        		"            try {\n" + 
        		"                request = JSON.parse(request);\n" + 
        		"            } catch {\n" + 
        		"                document.getElementById(\"result\").innerHTML = \"Request is an invalid JSON\";\n" + 
        		"                return;\n" + 
        		"            }\n" + 
        		"\n" + 
        		"            fetch('http://localhost:8080/movie', {\n" + 
        		"    method: 'post',\n" + 
        		"    headers: {\n" + 
        		"      'Content-Type': 'application/json'\n" + 
        		"      // 'Content-Type': 'application/x-www-form-urlencoded',\n" + 
        		"    },\n" + 
        		"    body: JSON.stringify(request)\n" + 
        		"  }).then(function(response) {\n" + 
        		"    response.json().then(function(data) {\n" + 
        		"        document.getElementById(\"result\").innerHTML = JSON.stringify(data,undefined,4);\n" + 
        		"       \n" + 
        		"  });\n" + 
        		"    \n" + 
        		"  })\n" + 
        		"\n" + 
        		"\n" + 
        		"            \n" + 
        		"        }\n" + 
        		"    </script>\n" + 
        		"\n" + 
        		"    <style> \n" + 
        		"    #result {\n" + 
        		"        height:500px;\n" + 
        		"        width:100%;\n" + 
        		"        overflow: scroll;\n" + 
        		"        background-color:antiquewhite;\n" + 
        		"        color:brown;\n" + 
        		"    }\n" + 
        		"    </style>\n" + 
        		"  </head>\n" + 
        		"  <body>\n" + 
        		"    <div class=\"container-fluid\">\n" + 
        		"        <div class=\"row\">\n" + 
        		"            <div class=\"col-sm\">\n" + 
        		"                <nav aria-label=\"breadcrumb\">\n" + 
        		"                    <ol class=\"breadcrumb\">\n" + 
        		"                      <li class=\"breadcrumb-item active\" aria-current=\"page\">Kiara Demo Project</li>\n" + 
        		"                    </ol>\n" + 
        		"                  </nav>\n" + 
        		"            </div>\n" + 
        		"        </div>\n" + 
        		"\n" + 
        		"          <div class=\"row\">\n" + 
        		"            <div class=\"col-sm\">\n" + 
        		"                <div class=\"input-group \">\n" + 
        		"                    <div class=\"input-group-prepend \">\n" + 
        		"                      <span class=\"input-group-text\">Write your JSON query here</span>\n" + 
        		"                    </div>\n" + 
        		"                    <textarea id=\"query\" rows=\"20\" class=\"form-control\" aria-label=\"With textarea\"></textarea>\n" + 
        		"                </div>\n" + 
        		"\n" + 
        		"\n" + 
        		"            </div>\n" + 
        		"            <div class=\"col-sm\">\n" + 
        		"                <div class=\"row\">\n" + 
        		"\n" + 
        		"\n" + 
        		"                    <pre id=\"result\" > Results will appear here!</pre>\n" + 
        		"        \n" + 
        		"                </div>\n" + 
        		"            </div>\n" + 
        		"          </div>\n" + 
        		"          <div class=\"row\">\n" + 
        		"            <div class=\"col-sm\">\n" + 
        		"                <button onclick=\"sendQuery()\" type=\"button\" class=\"btn btn-success btn-lg btn-block\">Send Query </button>\n" + 
        		"            </div>\n" + 
        		"            <div class=\"col-sm\">\n" + 
        		"            </div>\n" + 
        		"    </div>\n" + 
        		"\n" + 
        		"\n" + 
        		"    <!-- Optional JavaScript -->\n" + 
        		"    <!-- jQuery first, then Popper.js, then Bootstrap JS -->\n" + 
        		"    <script src=\"https://code.jquery.com/jquery-3.5.1.slim.min.js\" integrity=\"sha384-DfXdz2htPH0lsSSs5nCTpuj/zy4C+OGpamoFVy38MVBnE+IbbVYUew+OrCXaRkfj\" crossorigin=\"anonymous\"></script>\n" + 
        		"    <script src=\"https://cdn.jsdelivr.net/npm/popper.js@1.16.0/dist/umd/popper.min.js\" integrity=\"sha384-Q6E9RHvbIyZFJoft+2mJbHaEWldlvI9IOYy5n3zV9zzTtmI3UksdQRVvoxMfooAo\" crossorigin=\"anonymous\"></script>\n" + 
        		"    <script src=\"https://stackpath.bootstrapcdn.com/bootstrap/4.5.0/js/bootstrap.min.js\" integrity=\"sha384-OgVRvuATP1z7JjHLkuOU7Xw704+h835Lr+6QL9UvYjZE3Ipu6Tp75j7Bh/kR0JKI\" crossorigin=\"anonymous\"></script>\n" + 
        		"  </body>\n" + 
        		"</html>";
    }
}

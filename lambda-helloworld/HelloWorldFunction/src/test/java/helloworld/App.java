package helloworld;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class App implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom-Header", "application/json");

        String message = "hello world";
        String location = "unknown";

        try {
            // External call to get IP or similar info
            URL url = new URL("https://checkip.amazonaws.com");
            BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
            location = reader.readLine();

            String responseBody = String.format("{\"message\": \"%s\", \"location\": \"%s\"}", message, location);

            response.setStatusCode(200);
            response.setHeaders(headers);
            response.setBody(responseBody);
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatusCode(500);
            response.setBody("{\"message\": \"internal server error\"}");
        }

        return response;
    }
}

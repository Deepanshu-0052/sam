package helloworld;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

public class App implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(client);
    private final String TABLE_NAME = System.getenv("TABLE_NAME");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent().withHeaders(headers);

        try {
            if (input.getBody() == null) {
                return response.withStatusCode(400).withBody("{\"message\": \"Request body is missing\"}");
            }

            JSONObject body = new JSONObject(input.getBody());
            String deviceId = body.optString("device_id", null);
            String currentVersion = body.optString("os_version", null);

            if (deviceId == null || currentVersion == null) {
                return response.withStatusCode(400).withBody("{\"message\": \"Missing device_id or os_version\"}");
            }

            Table table = dynamoDB.getTable(TABLE_NAME);
            GetItemSpec spec = new GetItemSpec().withPrimaryKey("device_id", deviceId);
            Item item = table.getItem(spec);

            if (item == null) {
                return response.withStatusCode(404).withBody("{\"message\": \"Device not found\"}");
            }

            String latestVersion = item.getString("version_name");
            String downloadLink = item.getString("download_link");

            if (latestVersion == null || downloadLink == null) {
                return response.withStatusCode(500).withBody("{\"message\": \"Missing version info in database\"}");
            }

            if (currentVersion.equals(latestVersion)) {
                return response.withStatusCode(200).withBody("{\"update\": false, \"message\": \"Your OS is up to date\"}");
            } else {
                JSONObject result = new JSONObject();
                result.put("update", true);
                result.put("message", "Update available");
                result.put("download_link", downloadLink);
                return response.withStatusCode(200).withBody(result.toString());
            }

        } catch (Exception e) {
            e.printStackTrace();
            return response.withStatusCode(500).withBody("{\"message\": \"Internal server error\"}");
        }
    }
}

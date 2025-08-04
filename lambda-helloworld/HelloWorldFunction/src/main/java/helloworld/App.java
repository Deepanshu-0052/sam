package helloworld;

import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

import com.amazonaws.HttpMethod;
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
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;

public class App implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(client);
    private final AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();

    private final String VERSION_TABLE = System.getenv("TABLE_NAME");
    private final String LINK_TABLE = System.getenv("LINK_TABLE_NAME");

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

            Table versionTable = dynamoDB.getTable(VERSION_TABLE);
            GetItemSpec spec = new GetItemSpec().withPrimaryKey("device_id", deviceId);
            Item item = versionTable.getItem(spec);

            if (item == null) {
                return response.withStatusCode(404).withBody("{\"message\": \"Device not found\"}");
            }

            String latestVersion = item.getString("version_name");
            String s3Url = item.getString("download_link");

            if (latestVersion == null || s3Url == null) {
                return response.withStatusCode(500).withBody("{\"message\": \"Missing version info in database\"}");
            }

            if (currentVersion.equals(latestVersion)) {
                return response.withStatusCode(200).withBody("{\"update\": false, \"message\": \"Your OS is up to date\"}");
            } else {
                // Parse s3://bucket/key
                String s3Prefix = "s3://";
                if (!s3Url.startsWith(s3Prefix)) {
                    return response.withStatusCode(500).withBody("{\"message\": \"Invalid S3 URL format in database\"}");
                }

                String[] parts = s3Url.substring(s3Prefix.length()).split("/", 2);
                String bucket = parts[0];
                String key = parts[1];

                // Generate 2-hour presigned URL
                Date expiration = new Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000);
                GeneratePresignedUrlRequest generatePresignedUrlRequest = new GeneratePresignedUrlRequest(bucket, key)
                        .withMethod(HttpMethod.GET)
                        .withExpiration(expiration);

                URL presignedUrl = s3Client.generatePresignedUrl(generatePresignedUrlRequest);

                // Store in DynamoDB with TTL
                Table linkTable = dynamoDB.getTable(LINK_TABLE);
                long ttl = System.currentTimeMillis() / 1000 + 2 * 60 * 60;

                Item linkItem = new Item()
                        .withPrimaryKey("device_id", deviceId)
                        .withString("download_link", presignedUrl.toString())
                        .withNumber("ttl", ttl);

                linkTable.putItem(linkItem);

                JSONObject result = new JSONObject();
                result.put("update", true);
                result.put("message", "Update available");
                result.put("download_link", presignedUrl.toString());

                return response.withStatusCode(200).withBody(result.toString());
            }

        } catch (Exception e) {
            e.printStackTrace();
            return response.withStatusCode(500).withBody("{\"message\": \"Internal server error\"}");
        }
    }
}

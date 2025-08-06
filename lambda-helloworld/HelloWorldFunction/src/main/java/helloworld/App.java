package helloworld;

import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.ScanOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
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

    private final String FIRMWARE_TABLE = System.getenv("FIRMWARE_TABLE");
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

            Table firmwareTable = dynamoDB.getTable(FIRMWARE_TABLE);

            // Scan for all versions for this device
            ScanSpec scanSpec = new ScanSpec()
                .withFilterExpression("name = :d")
                .withValueMap(new ValueMap().withString(":d", deviceId));

            ItemCollection<ScanOutcome> items = firmwareTable.scan(scanSpec);

            List<Item> itemList = new ArrayList<>();
            for (Item item : items) {
                itemList.add(item);
            }

            if (itemList.isEmpty()) {
                return response.withStatusCode(404).withBody("{\"message\": \"No firmware found for device\"}");
            }

            // Sort by uploadedAt descending to get latest
            Item latestItem = itemList.stream()
                .max(Comparator.comparingLong(i -> i.getLong("uploadedAt")))
                .orElse(null);

            if (latestItem == null) {
                return response.withStatusCode(500).withBody("{\"message\": \"Failed to determine latest firmware\"}");
            }

            String latestVersion = latestItem.getString("version");
            String fileUrl = latestItem.getString("fileUrl");

            if (latestVersion == null || fileUrl == null) {
                return response.withStatusCode(500).withBody("{\"message\": \"Missing data in firmware record\"}");
            }

            if (currentVersion.equals(latestVersion)) {
                return response.withStatusCode(200).withBody("{\"update\": false, \"message\": \"Your OS is up to date\"}");
            }

            // Extract bucket and key from URL
            String bucket = System.getenv("BUCKET_NAME");

            String s3Prefix = "https://" + bucket + ".s3.ap-south-1.amazonaws.com/";
            if (!fileUrl.startsWith(s3Prefix)) {
                return response.withStatusCode(500).withBody("{\"message\": \"Invalid S3 URL format\"}");
            }

            String key = fileUrl.substring(s3Prefix.length());

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
            result.put("latest_version", latestVersion);

            return response.withStatusCode(200).withBody(result.toString());

        } catch (Exception e) {
            e.printStackTrace();
            return response.withStatusCode(500).withBody("{\"message\": \"Internal server error\"}");
        }
    }
}

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Properties;
import java.nio.charset.StandardCharsets;

public class SimpleWebServer {
    private static int PORT;
    private static int MAX_THREADS;
    private static String ROOT_DIRECTORY;
    private static String DEFAULT_PAGE;

    public static void main(String[] args) {
        loadConfig();

        ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server listening on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Runnable worker = new WebServerRunnable(clientSocket);
                executor.execute(worker);
            }
        } catch (IOException e) {
            System.err.println("Error starting the server: " + e.getMessage());
        }
    }

    private static void loadConfig() {
        try (InputStream input = new FileInputStream("config.ini")) {
            Properties prop = new Properties();
            prop.load(input);

            PORT = Integer.parseInt(prop.getProperty("PORT"));
            MAX_THREADS = Integer.parseInt(prop.getProperty("MAX_THREADS"));
            ROOT_DIRECTORY = prop.getProperty("ROOT_DIRECTORY");
            DEFAULT_PAGE = prop.getProperty("DEFAULT_PAGE");
        } catch (IOException | NumberFormatException e) {
            System.err.println("Error loading configuration: " + e.getMessage());
            System.exit(1);
        }
    }

    private static class WebServerRunnable implements Runnable {
        private Socket clientSocket;

        public WebServerRunnable(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try (
                BufferedReader inFromClient = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                DataOutputStream outToClient = new DataOutputStream(clientSocket.getOutputStream())
            ) {
                // Read the HTTP request and parse it
                HttpRequest httpRequest = parseHttpRequest(inFromClient);

                // Process the request and generate the HTTP response
                String httpResponse = processHttpRequest(httpRequest);

                // Send the response back to the client
                outToClient.writeBytes(httpResponse);
            } catch (IOException e) {
                System.err.println("Error handling client request: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
            }
        }

        private static HttpRequest parseHttpRequest(BufferedReader inFromClient) throws IOException {
            // Implement parsing logic here
            String requestLine = inFromClient.readLine();
            if (requestLine != null) {
                String[] requestParts = requestLine.split(" ");
                String method = requestParts[0];
                String requestedPage = requestParts[1];
        
                Map<String, String> parameters = new HashMap<>();
                boolean isImage = false; // Assuming it's false by default
                int contentLength = 0;
                String referer = "";
                String userAgent = "";
        
                if (method.equals("GET")) {
                    // Parse parameters from the URL for GET requests
                    int questionMarkIndex = requestedPage.indexOf('?');
                    if (questionMarkIndex != -1) {
                        String paramString = requestedPage.substring(questionMarkIndex + 1);
                        String[] paramPairs = paramString.split("&");
                        for (String paramPair : paramPairs) {
                            String[] keyValue = paramPair.split("=");
                            if (keyValue.length == 2) {
                                parameters.put(keyValue[0], keyValue[1]);
                            }
                        }
                    }
                } else if (method.equals("POST")) {
                    // Parse parameters from the request body for POST requests
                    while (true) {
                        String line = inFromClient.readLine();
                        if (line == null || line.isEmpty()) {
                            break;
                        }
                        if (line.startsWith("Content-Length:")) {
                            contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                        } else if (line.startsWith("Referer:")) {
                            referer = line.substring("Referer:".length()).trim();
                        } else if (line.startsWith("User-Agent:")) {
                            userAgent = line.substring("User-Agent:".length()).trim();
                        }
                    }
        
                    // Additional logic to determine if it's an image request
                    // For simplicity, let's assume any requested page ending with .jpg, .bmp, or .gif is considered an image
                    if (requestedPage.endsWith(".jpg") || requestedPage.endsWith(".bmp") || requestedPage.endsWith(".gif")) {
                        isImage = true;
                    }
        
                    if (contentLength > 0) {
                        char[] charBuffer = new char[contentLength];
                        inFromClient.read(charBuffer, 0, contentLength);
                        String requestBody = new String(charBuffer);
                        String[] paramPairs = requestBody.split("&");
                        for (String paramPair : paramPairs) {
                            String[] keyValue = paramPair.split("=");
                            if (keyValue.length == 2) {
                                parameters.put(keyValue[0], keyValue[1]);
                            }
                        }
                    }
                }
        
                return new SimpleWebServer.HttpRequest(method, requestedPage, isImage, contentLength, referer, userAgent, parameters);
            }
        
            return null;
        }

        private String processHttpRequest(HttpRequest httpRequest) {
            if (httpRequest != null) {
                
                if (!httpRequest.getMethod().equals("GET") && !httpRequest.getMethod().equals("POST")) {
                    // Unsupported method, return a 501 response
                    return "HTTP/1.1 501 Not Implemented\r\nContent-Type: text/html\r\n\r\n501 Not Implemented";
                }

                if (httpRequest.getMethod().equals("POST") && httpRequest.getRequestedPage().equals("/submit")) {
                    // Process the form data from the POST request
                    Map<String, String> formData = httpRequest.getParameters();
                
                    // Iterate through the form data and print each field
                    for (Map.Entry<String, String> entry : formData.entrySet()) {
                        String fieldName = entry.getKey();
                        String fieldValue = entry.getValue();
                
                        // Do something with the form data (e.g., print to console)
                        System.out.println(fieldName + ": " + fieldValue);
                    }
                
                    // Send a response back to the client
                    return "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nForm submitted successfully!";
                }                

                System.out.println("Received HTTP request: " + httpRequest);

                String requestedFile = ROOT_DIRECTORY + httpRequest.getRequestedPage();

                // Check if the requested path is a directory
                if (requestedFile.endsWith("/") || requestedFile.endsWith("\\")) {
                    // If it's a directory, append the default page
                    requestedFile += DEFAULT_PAGE;
                }

                // Check if the requested file exists
                File file = new File(requestedFile);

                if (file.exists() && !file.isDirectory()) {
                    // Serve the file
                    return serveFile(file);
                } else {
                    // File not found, return a 404 response
                    return "HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\n\r\n404 Not Found";
                }
            }

            // Bad Request response
            return "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html\r\n\r\nBad Request";
        }

        private String serveFile(File file) {
            try {
                FileInputStream fis = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                fis.close();

                String contentType = getContentType(file);

                // Build the HTTP response with file content
                StringBuilder response = new StringBuilder();
                response.append("HTTP/1.1 200 OK\r\n");
                response.append("Content-Type: ").append(contentType).append("\r\n");
                response.append("Content-Length: ").append(file.length()).append("\r\n");
                response.append("\r\n");

                return response.toString() + new String(data, StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
                return "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/html\r\n\r\nInternal Server Error";
            }
        }

        private String getContentType(File file) {
            // Add more content types as needed
            if (file.getName().endsWith(".html") || file.getName().endsWith(".htm")) {
                return "text/html";
            } else if (file.getName().endsWith(".jpg") || file.getName().endsWith(".jpeg")) {
                return "image/jpeg";
            } else if (file.getName().endsWith(".png")) {
                return "image/png";
            } else if (file.getName().endsWith(".gif")) {
                return "image/gif";
            } else if (file.getName().endsWith(".txt")) {
                return "text/plain";
            } else {
                return "application/octet-stream";
            }
        }
    }

    public static class HttpRequest {
        private final String method;
        private final String requestedPage;
        private final Boolean isImage;
        private final int contentLength;
        private final String referer;
        private final String userAgent;
        private final Map<String, String> parameters;

        public HttpRequest(String method, String requestedPage, Boolean isImage, int contentLength, String referer, String userAgent, Map<String, String> parameters) {
            this.method = method;
            this.requestedPage = requestedPage;
            this.isImage = isImage;
            this.contentLength = contentLength;
            this.referer = referer;
            this.userAgent = userAgent;
            this.parameters = parameters;
        }

        public String getMethod() {
            return method;
        }

        public String getRequestedPage() {
            return requestedPage;
        }

        public Boolean isImage() {
            return isImage;
        }

        public int getContentLength() {
            return contentLength;
        }

        public String getReferer() {
            return referer;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        @Override
        public String toString() {
            return "HttpRequest{" +
                    "method='" + method + '\'' +
                    ", requestedPage='" + requestedPage + '\'' +
                    ", isImage=" + isImage +
                    ", contentLength=" + contentLength +
                    ", referer='" + referer + '\'' +
                    ", userAgent='" + userAgent + '\'' +
                    ", parameters=" + parameters +
                    '}';
        }
    }
}

package com.capco.bots.faq;

import com.capco.bots.IBotHandler;
import com.capco.bots.IBotsEnum;
import com.capco.bots.faq.data.Docs;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.APPEND;

/**
 * Handles FAQs
 * Created by vijayalaxmi on 23/6/2017.
 */
public class FAQHandler implements IBotHandler {

    private static Logger logger = LogManager.getLogger(FAQHandler.class);

    private final String unansweredQuestionFilePath;

    public FAQHandler(String unansweredQuestionFilePath) {
        this.unansweredQuestionFilePath = unansweredQuestionFilePath;
        logger.debug("Unanswered questions will be logged in {}", unansweredQuestionFilePath);
    }

    @Override
    public void init() {
        logger.debug("FAQHandler init");
    }

    @Override
    public String processMessage(String message) {
        logger.debug("Processing message :" + message);
        if (message.trim().toLowerCase().equals("hi") || message.trim().toLowerCase().equals("hello")) {
            return message.trim() + "! I am FAQBot and can help you find answers for FAQs related to Capco. Please enter your question or partial question with keywords. ";
        }

        String result;
        try {
            String queryableMessage = convertToQueryable(message);
            URL url = generateQueryURL(queryableMessage);
            result = queryFAQWebService(message, url);
        } catch (IOException e) {
            result = "Unable to process :" + message;
            logger.error("Error while processing message : {}", message, e);
        }
        logger.debug("returning result :" + result);
        return result;
    }

    private String queryFAQWebService(String originalMessage, URL url) throws IOException {
        StringBuffer result = new StringBuffer();
        HttpURLConnection conn = getHttpURLConnection(url);
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
            String output;
            Map<String, String> questionAnswersMap = new HashMap<>();
            logger.debug("Output from Server .... \n");
            while ((output = br.readLine()) != null) {
                logger.debug("Received raw reply from Server : {}", output);
                Map<String, String> parseMap = parseResponse(output);
                logger.debug("parsed reply from server : {}", parseMap);
                if(parseMap.isEmpty()) {
                    logUnansweredQuestion(originalMessage);
                    result.append("No answer found for your question. Please contact admin.");
                }else{
                    questionAnswersMap.putAll(parseMap);
                    questionAnswersMap.forEach((Q, A) -> result.append(System.lineSeparator()).append(Q).append(System.lineSeparator()).append(A).append(System.lineSeparator()));
                }
                logger.debug("QuestionAnswersMap : {}", questionAnswersMap);
            }
        } finally {
            conn.disconnect();
        }
        return result.toString();
    }

    private HttpURLConnection getHttpURLConnection(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("Failed : HTTP error code : "+ conn.getResponseCode());
        }
        return conn;
    }

    private void logUnansweredQuestion(String message) throws IOException {
        Files.write(Paths.get(unansweredQuestionFilePath), (System.lineSeparator() + new Date() + " " + message).getBytes(), APPEND);
    }

    private URL generateQueryURL(String message) throws MalformedURLException {
        //TODO host and port should be extracted as part of properties file
        URL url = new URL("http://localhost:8983/solr/answer?q=" + message + "%3F&defType=qa&qa=true&qa.qf=doctitle&wt=json");
        logger.debug("Querying FAQ webservice using URL : {}", url);
        return url;
    }

    private String convertToQueryable(String message) {
        message = message.replaceAll(" ", "+");
        return message;
    }

    Map<String, String> parseResponse(String response) throws IOException {
        String questionResponse = "response";
        JsonFactory factory = new JsonFactory();
        ObjectMapper mapper = new ObjectMapper(factory);
        JsonNode rootNode = mapper.readTree(response);

        Map<Integer, String> questionsMap = new HashMap<>();
        Map<Integer, String> answersMap = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fieldsIterator = rootNode.fields();
        while (fieldsIterator.hasNext()) {
            Map.Entry<String, JsonNode> field = fieldsIterator.next();
            if (field.getKey().equals(questionResponse)) {
                Docs[] docs = new ObjectMapper().readValue(field.getValue().get("docs").toString(), Docs[].class);
                questionsMap = Stream.of(docs)
                        .collect(Collectors.toMap(p -> Integer.parseInt(p.getDocid()),
                                p -> "Question: " + p.getDoctitle()[0]));
                answersMap = Stream.of(docs)
                        .collect(Collectors.toMap(p -> Integer.parseInt(p.getDocid()),
                                p -> "Answer: " + p.getBody()[0]));

            }
        }

        return questionsMap.keySet().stream().collect(Collectors.toMap(questionsMap::get, answersMap::get));
    }

    @Override
    public String getId() {
        return IBotsEnum.FAQ.toString();
    }
}

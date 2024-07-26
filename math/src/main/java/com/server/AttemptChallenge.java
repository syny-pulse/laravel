package com.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AttemptChallenge {
    static String attemptChallenge(int challengeNo, PrintWriter out, BufferedReader in, int participantID) throws SQLException, IOException {
        System.out.println(challengeNo + " " + participantID);

        // Fetch challenge details
        String Sql = "SELECT attemptDuration, challengeName FROM Challenges WHERE challengeNo = ? AND openDate <= CURDATE() AND closeDate >= CURDATE()";
        try (PreparedStatement stmt = Server.conn.prepareStatement(Sql)) {
            stmt.setInt(1, challengeNo);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return "Challenge is not open or does not exist.";
                } else {
                    // Check number of attempts
                    if (hasExceededAttempts(challengeNo, participantID)) {
                        return "You have already attempted this challenge 3 times.";
                    } else {
                        String attemptDurationStr = rs.getString("attemptDuration");
                        LocalTime attemptDuration = LocalTime.parse(attemptDurationStr, DateTimeFormatter.ofPattern("HH:mm:ss"));
                        long durationInSeconds = attemptDuration.toSecondOfDay();
                        String challengeName = rs.getString("challengeName");

                        List<Map<String, Object>> questions = fetchRandomQuestions(challengeNo);

                        String description = String.format("Challenge: %s\nDuration: %s",
                                challengeName, attemptDuration.toString());

                        out.println(description);
                        out.flush();

                        String startResponse = in.readLine();
                        if (!startResponse.equalsIgnoreCase("start")) {
                            return "Challenge cancelled.";
                        }

                        int attemptID = storeAttempt(challengeNo, participantID);
                        return conductChallenge(questions, durationInSeconds, attemptID, out, in);
                    }
                }
            }
        }
    }

    static String conductChallenge(List<Map<String, Object>> questions, long durationInSeconds,
                                   int attemptID, PrintWriter out, BufferedReader in) throws IOException, SQLException {
        int totalScore = 0;
        int totalMarks = 0;
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (durationInSeconds * 1000);

        for (int i = 0; i < questions.size(); i++) {
            Map<String, Object> question = questions.get(i);
            long currentTime = System.currentTimeMillis();
            if (currentTime >= endTime) {
                out.println("Time's up!");
                out.flush();
                break;
            }

            long remainingTime = endTime - currentTime;
            out.println(String.format("Question %d/%d", i + 1, questions.size()));
            out.println(question.get("question"));
            out.println(String.format("Remaining time: %s", formatDuration(remainingTime)));
            out.println("Enter your answer or '-' to skip:");
            out.flush();

            String userAnswer = readLineWithTimeout(remainingTime, in);
            if (userAnswer == null) {
                out.println("Time's up for this question!");
                out.flush();
                userAnswer = "-";
            }

            int questionNo = (int) question.get("questionNo");
            int answerNo = (int) question.get("answerNo");
            int maxScore = question.get("marksAwarded") != null ? (int) question.get("marksAwarded") : 0;
            storeAttemptQuestion(attemptID, questionNo, answerNo, maxScore, userAnswer);
            
            // Update totalScore after storing the attempt question
            String getScoreSql = "SELECT score FROM AttemptQuestions WHERE attemptID = ? AND questionNo = ?";
            try (PreparedStatement scoreStmt = Server.conn.prepareStatement(getScoreSql)) {
                scoreStmt.setInt(1, attemptID);
                scoreStmt.setInt(2, questionNo);
                ResultSet scoreRs = scoreStmt.executeQuery();
                if (scoreRs.next()) {
                    totalScore += scoreRs.getInt("score");
                }
            }
            
            totalMarks += maxScore;

            out.println("Answer recorded. Moving to next question...");
            out.flush();
        }

        out.println("END_OF_CHALLENGE");
        out.flush();

        reevaluateAttempt(attemptID);

        double percentageMark = (double) totalScore / totalMarks * 100;
        saveAttemptResult(attemptID, startTime, totalScore, percentageMark);

        return String.format("Challenge completed. Your score: %d (%.2f%%)", totalScore, percentageMark);
    }

    static String readLineWithTimeout(long timeoutMillis, BufferedReader in) throws IOException {
        long startTime = System.currentTimeMillis();
        StringBuilder input = new StringBuilder();
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (in.ready()) {
                int c = in.read();
                if (c == -1 || c == '\n') {
                    break;
                }
                input.append((char) c);
            }
            try {
                Thread.sleep(100); // Small delay to prevent busy-waiting
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return input.length() > 0 ? input.toString() : null;
    }

    static boolean hasExceededAttempts(int challengeNo, int participantID) throws SQLException {
        String checkAttemptsSql = "SELECT COUNT(*) as attempts FROM Attempts WHERE challengeNo = ? AND participantID = ?";
        try (PreparedStatement attemptStmt = Server.conn.prepareStatement(checkAttemptsSql)) {
            attemptStmt.setInt(1, challengeNo);
            attemptStmt.setInt(2, participantID);
            ResultSet attemptRs = attemptStmt.executeQuery();
            return attemptRs.next() && attemptRs.getInt("attempts") >= 3;
        }
    }

    static List<Map<String, Object>> fetchRandomQuestions(int challengeNo) throws SQLException {
        String questionSql = "SELECT q.questionNo, q.question, a.answerNo, a.marksAwarded FROM Questions q " +
                             "JOIN Answer a ON q.questionNo = a.questionNo " +
                             "WHERE q.questionBankID = (SELECT questionBankID FROM Challenges WHERE challengeNo = ?) " +
                             "ORDER BY RAND() LIMIT 10";
        List<Map<String, Object>> questions = new ArrayList<>();
        try (PreparedStatement questionStmt = Server.conn.prepareStatement(questionSql)) {
            questionStmt.setInt(1, challengeNo);
            ResultSet questionRs = questionStmt.executeQuery();
            while (questionRs.next()) {
                Map<String, Object> question = new HashMap<>();
                question.put("questionNo", questionRs.getInt("questionNo"));
                question.put("question", questionRs.getString("question"));
                question.put("answerNo", questionRs.getInt("answerNo"));
                question.put("marksAwarded", questionRs.getInt("marksAwarded"));
                questions.add(question);
            }
        }
        return questions;
    }

    static int storeAttempt(int challengeNo, int participantID) throws SQLException {
        String insertAttemptSql = "INSERT INTO Attempts (startTime, participantID, challengeNo, endTime, score, percentageMark) VALUES (?, ?, ?, NULL, NULL, NULL)";
        try (PreparedStatement pstmt = Server.conn.prepareStatement(insertAttemptSql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            pstmt.setInt(2, participantID);
            pstmt.setInt(3, challengeNo);
            pstmt.executeUpdate();

            try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1);
                } else {
                    throw new SQLException("Creating attempt failed, no ID obtained.");
                }
            }
        }
    }

    static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    static void storeAttemptQuestion(int attemptID, int questionNo, int answerNo, int maxScore, String givenAnswer) throws SQLException {
        String selectSql = "SELECT answer FROM Answers WHERE answerNo = ?";
        String correctAnswer;
       
        // Fetch the correct answer
        try (PreparedStatement selectStmt = Server.conn.prepareStatement(selectSql)) {
            selectStmt.setInt(1, answerNo);
            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                correctAnswer = rs.getString("answer");
            } else {
                throw new SQLException("No answer found for answerNo " + answerNo);
            }
        }
       
        // Calculate the actual score
        int actualScore;
        if (givenAnswer.equals("-")) {
            actualScore = 0;
        } else {
            try {
                int givenAnswerInt = Integer.parseInt(givenAnswer);
                int correctAnswerInt = Integer.parseInt(correctAnswer);
                if (givenAnswerInt == correctAnswerInt) {
                    actualScore = 10;
                } else {
                    actualScore = -3;
                }
            } catch (NumberFormatException e) {
                // If parsing fails, compare as strings
                if (givenAnswer.equalsIgnoreCase(correctAnswer)) {
                    actualScore = 10;
                } else {
                    actualScore = -3;
                }
            }
        }
    
        System.out.println("Correct answer: " + correctAnswer);
        System.out.println("Actual score: " + actualScore);
    
        // Insert the attempt question with the correct answer and calculated score
        String insertSql = "INSERT INTO AttemptQuestions (attemptID, questionNo, score, givenAnswer, correctAns) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = Server.conn.prepareStatement(insertSql)) {
            pstmt.setInt(1, attemptID);
            pstmt.setInt(2, questionNo);
            pstmt.setInt(3, actualScore);
            pstmt.setString(4, givenAnswer);
            pstmt.setString(5, correctAnswer);
            pstmt.executeUpdate();
        }
    }
    static void saveAttemptResult(int attemptID, long startTime, int totalScore, double percentageMark) throws SQLException {
        String saveAttemptSql = "UPDATE Attempts SET endTime = ?, score = ?, percentageMark = ? WHERE attemptID = ?";
        try (PreparedStatement saveStmt = Server.conn.prepareStatement(saveAttemptSql)) {
            saveStmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            saveStmt.setInt(2, totalScore);
            saveStmt.setDouble(3, percentageMark);
            saveStmt.setInt(4, attemptID);
            saveStmt.executeUpdate();
        }
    }

    static void reevaluateAttempt(int attemptID) throws SQLException {
        String selectSql = "SELECT questionNo, givenAnswer, correctAns FROM AttemptQuestions WHERE attemptID = ?";
        String updateSql = "UPDATE AttemptQuestions SET score = ? WHERE attemptID = ? AND questionNo = ?";
        int totalScore = 0;
    
        try (PreparedStatement selectStmt = Server.conn.prepareStatement(selectSql);
             PreparedStatement updateStmt = Server.conn.prepareStatement(updateSql)) {
            
            selectStmt.setInt(1, attemptID);
            ResultSet rs = selectStmt.executeQuery();
    
            while (rs.next()) {
                int questionNo = rs.getInt("questionNo");
                String givenAnswer = rs.getString("givenAnswer");
                String correctAnswer = rs.getString("correctAns");
                
                int score;
                if (givenAnswer.equals("-")) {
                    score = 0;
                } else if (givenAnswer.equalsIgnoreCase(correctAnswer)) {
                    score = 10;
                } else {
                    score = -3;
                }
    
                updateStmt.setInt(1, score);
                updateStmt.setInt(2, attemptID);
                updateStmt.setInt(3, questionNo);
                updateStmt.executeUpdate();
    
                totalScore += score;
            }
        }
    }
}
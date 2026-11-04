package com.superagent.web;

import com.superagent.api.ApiResponse;
import com.superagent.web.agent.AgentNotFoundException;
import com.superagent.web.attachment.AttachmentNotFoundException;
import com.superagent.web.automation.AutomationForbiddenException;
import com.superagent.web.automation.AutomationNotFoundException;
import com.superagent.web.chat.SessionNotFoundException;
import com.superagent.web.connector.ConnectorNotFoundException;
import com.superagent.web.knowledge.KnowledgeConflictException;
import com.superagent.web.knowledge.KnowledgeNotFoundException;
import com.superagent.web.provider.ProviderAlreadyAddedException;
import com.superagent.web.provider.ProviderNotFoundException;
import com.superagent.web.skill.SkillForbiddenException;
import com.superagent.web.skill.SkillNotFoundException;
import com.superagent.web.task.ArtifactNotFoundException;
import com.superagent.web.task.TaskBusyException;
import com.superagent.web.task.TaskNotFoundException;
import com.superagent.web.user.UserNotFoundException;
import com.superagent.web.user.UsernameTakenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Single central point for turning exceptions into the {@link ApiResponse}
 * envelope: business code lets exceptions propagate here, where they are logged
 * and reported to the client with a specific message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error("invalid_argument", detail));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", "No such endpoint"));
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleSessionNotFound(SessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(AgentNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAgentNotFound(AgentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(ConnectorNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleConnectorNotFound(ConnectorNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleTaskNotFound(TaskNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(ArtifactNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleArtifactNotFound(ArtifactNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(AutomationNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAutomationNotFound(AutomationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(AutomationForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleAutomationForbidden(AutomationForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("forbidden", ex.getMessage()));
    }

    @ExceptionHandler(AttachmentNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAttachmentNotFound(AttachmentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(TaskBusyException.class)
    public ResponseEntity<ApiResponse<Void>> handleTaskBusy(TaskBusyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("conflict", ex.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<ApiResponse<Void>> handleUsernameTaken(UsernameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("conflict", ex.getMessage()));
    }

    @ExceptionHandler(ProviderNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleProviderNotFound(ProviderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(ProviderAlreadyAddedException.class)
    public ResponseEntity<ApiResponse<Void>> handleProviderAlreadyAdded(ProviderAlreadyAddedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("conflict", ex.getMessage()));
    }

    @ExceptionHandler(SkillNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleSkillNotFound(SkillNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(SkillForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleSkillForbidden(SkillForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("forbidden", ex.getMessage()));
    }

    @ExceptionHandler(KnowledgeNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleKnowledgeNotFound(KnowledgeNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("not_found", ex.getMessage()));
    }

    @ExceptionHandler(KnowledgeConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleKnowledgeConflict(KnowledgeConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("conflict", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("invalid_argument", ex.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("method_not_allowed", ex.getMethod() + " is not supported here"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("invalid_argument", "Parameter '" + ex.getName() + "' has the wrong type"));
    }

    /**
     * The client (e.g. an SSE reader) disconnected mid-response. The container
     * notifies us, but there is no usable response left to write — any attempt
     * would fail against the already-committed {@code text/event-stream}. Return
     * void so nothing is written, and record it at debug: this is expected, not an error.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.debug("SSE client disconnected: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("internal_error", detail));
    }
}

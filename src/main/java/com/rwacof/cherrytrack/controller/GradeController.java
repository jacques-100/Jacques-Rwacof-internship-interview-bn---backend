package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.GradeDtos.GradeDto;
import com.rwacof.cherrytrack.dto.GradeDtos.GradeRequest;
import com.rwacof.cherrytrack.service.GradeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/grades")
@Tag(name = "Grades")
public class GradeController {

    private final GradeService gradeService;

    public GradeController(GradeService gradeService) {
        this.gradeService = gradeService;
    }

    /** Readable by every role: clerks pick from these when grading. */
    @GetMapping
    public List<GradeDto> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return gradeService.list(activeOnly);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('GRADE_MANAGE')")
    public ResponseEntity<GradeDto> create(@Valid @RequestBody GradeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(gradeService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('GRADE_MANAGE')")
    public GradeDto update(@PathVariable Long id, @Valid @RequestBody GradeRequest request) {
        return gradeService.update(id, request);
    }
}

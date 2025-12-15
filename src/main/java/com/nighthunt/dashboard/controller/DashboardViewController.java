package com.nighthunt.dashboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Dashboard View Controller
 * Serves the dashboard HTML page
 */
@Controller
public class DashboardViewController {
    
    /**
     * Serve dashboard.html
     * GET /dashboard or /dashboard.html
     */
    @GetMapping({"/dashboard", "/dashboard.html"})
    public String dashboard() {
        return "forward:/dashboard.html";
    }
}


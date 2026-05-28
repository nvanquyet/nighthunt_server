package com.nighthunt.dashboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Dashboard View Controller
 * Redirects legacy /dashboard and /dashboard.html paths to the admin dashboard.
 */
@Controller
public class DashboardViewController {

    @GetMapping({"/dashboard", "/dashboard.html"})
    public String dashboard() {
        return "redirect:https://dashboard.vawnwuyest.me";
    }
}


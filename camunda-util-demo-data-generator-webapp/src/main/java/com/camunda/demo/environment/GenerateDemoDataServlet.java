package com.camunda.demo.environment;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.camunda.bpm.BpmPlatform;

import com.camunda.demo.environment.simulation.TimeAwareDemoGenerator;

/**
 * Using a plain servlet to avoid any environment dependency - but to callable
 * by HTTP.
 * 
 * Input:
 * <ul>
 * <li>processDefinitionKey: Process Definition Key (latest version is
 * used)</li>
 * <li>numberOfDaysInPast: Time Frame - days in the past</li>
 * <li>timeBetweenStartsBusinessDaysMean: Distribution []</li>
 * <li>timeBetweenStartsBusinessDaysSd: (Standard Deviation)</li>
 * </ul>
 * 
 * @author ruecker
 */
@WebServlet(value = "/generate", loadOnStartup = 1)
public class GenerateDemoDataServlet extends HttpServlet {

  private static final Logger log = Logger.getLogger(GenerateDemoDataServlet.class.getName());

  private static final long serialVersionUID = 1L;

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    doGet(req, resp);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {    String processDefinitionKey = req.getParameter("processDefinitionKey");
    String[] additionalDefinitionKeys = req.getParameter("additionalProcessDefinitionKeys").split(",\\s*");
    int numberOfDaysInPast = Integer.parseInt(req.getParameter("numberOfDaysInPast"));
    double timeBetweenStartsBusinessDaysMean = Double.parseDouble(req.getParameter("timeBetweenStartsBusinessDaysMean"));
    double timeBetweenStartsBusinessDaysSd = Double.parseDouble(req.getParameter("timeBetweenStartsBusinessDaysSd"));
    String startBusinessDayAt = req.getParameter("startBusinessDayAtHour") + ":" + req.getParameter("startBusinessDayAtMinute");
    String endBusinessDayAt = req.getParameter("endBusinessDayAtHour") + ":" + req.getParameter("endBusinessDayAtMinute");
    boolean includeWeekend = req.getParameter("includeWeekend").toLowerCase().equals("true");
    boolean runAlways = req.getParameter("runAlways").toLowerCase().equals("true");

    log.info("start generate data");
    new TimeAwareDemoGenerator(BpmPlatform.getDefaultProcessEngine(), null, CamundaBpmProcessApplication.processApplicationReference) //
        .processDefinitionKey(processDefinitionKey) //
        .additionalModelKeys(additionalDefinitionKeys) //
        .numberOfDaysInPast(numberOfDaysInPast) //
        .timeBetweenStartsBusinessDays(timeBetweenStartsBusinessDaysMean, timeBetweenStartsBusinessDaysSd) //
        .startTimeBusinessDay(startBusinessDayAt) //
        .endTimeBusinessDay(endBusinessDayAt) //
        .includeWeekend(includeWeekend)//
        .runAlways(runAlways) //
        .run();

    log.info("data generation finished");

    resp.sendRedirect("index.html");
  }

}

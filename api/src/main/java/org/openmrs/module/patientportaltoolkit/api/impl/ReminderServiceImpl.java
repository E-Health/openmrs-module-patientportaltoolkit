package org.openmrs.module.patientportaltoolkit.api.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.patientportaltoolkit.Guideline;
import org.openmrs.module.patientportaltoolkit.Reminder;
import org.openmrs.module.patientportaltoolkit.api.GuidelineService;
import org.openmrs.module.patientportaltoolkit.api.ReminderService;
import org.openmrs.module.patientportaltoolkit.api.db.ReminderDAO;

import java.text.ParseException;
import java.util.*;

/**
 * Created by Maurya on 08/06/2015.
 */
public class ReminderServiceImpl extends BaseOpenmrsService implements ReminderService {

    protected ReminderDAO dao;
    private final static Integer CANCER_ABNORMALITY_TOLD = 162826;
    private final static Integer CANCER_ABNORMALITY_TOLD_YES = 1065;
    private final static Integer CANCER_ABNORMALITY = 162870;
    private final static Integer CANCER_ABNORMALITY_FAP = 162828;
    private final static Integer CANCER_ABNORMALITY_HNPCC = 162827;
    private final static Integer CANCER_ABNORMALITY_INFLBD = 162829;

    private final static Integer CANCER_TYPE = 162869;
    private final static Integer CANCER_STAGE = 162875;
    private final static Integer SURGERY_TYPE = 162876;
    private final static Integer SURGERY_DATE = 162842;
    private final static Integer DIAGNOSIS_DATE = 162825;
    private final static Integer RADIATION_TYPE = 162878;
    private final static Integer RADIATION_START_DATE = 6132;
    private final static Integer CHEMOTHERAPY_MEDS = 6156;
    private final static int ALERT_DAYS = 60;
    private final static int MATCH_DAYS = 7;

    protected final Log log = LogFactory.getLog(this.getClass());

    /**
     * @return the dao
     */
    public ReminderDAO getDao() {
        return dao;
    }

    /**
     * @param dao the dao to set
     */
    public void setDao(ReminderDAO dao) {
        this.dao = dao;
    }

    @Override
    public List<Reminder> getReminders(Patient pat) {
        return findReminders(pat);
    }

    @Override
    public List<Reminder> getReminders(Patient pat, Date indexDate) {
        return findReminders(pat, indexDate);
    }

    @Override
    public List<Reminder> getAllRemindersByPatient(Patient patient) {
        return dao.getAllRemindersByPatient(patient);
    }


    /*
     * Find specific reminders for alerting purpose for a given patient at a given date based on ALERT_DAYS (e.g. 60 days) alerting rule
     *
     * Alert display logic:
     *
     * find reminders by guideline -> find and exclude reminders completed/matched, snoozed, scheduled, or not performed ->
     * find reminders by providers -> exclude reminders completed/matched, snoozed, scheduled, or not performed for provider-recommended reminders ->
     * add provider recommended reminder alerts (overriding corresponding guidline reminder alerts to keep just one alert of the same kind)
     *
     * Note: 1. one completed test can match up to two reminders, one from guideline (middle-date rule), the other from providers (7-day-proximity rule)
     *       2. alert date is based on 60-day rule for both types of reminders (by guideline, and by providers)
     *
     */
    private List<Reminder> findReminders(Patient pat, Date indexDate) {
        //find all follow-up care guidelines
        List<Guideline> guidelines = Context.getService(GuidelineService.class).findGuidelines(pat);

        //find all reminders sorted by target date
        List<Reminder> reminders = findAllReminders(pat);

        //find all completed reminders
        List<Reminder> remindersCompleted = getRemindersCompleted(pat);

        //mark completed reminders (will take prevalence over other marks)
        if(remindersCompleted != null) {
            for(int ii = 0; ii< reminders.size(); ii++) {
                Reminder reminder = reminders.get(ii);
                Reminder nextReminder = null;
                Reminder previousReminder = null;

                for(int jj=ii+1; jj<reminders.size(); jj++) {
                    nextReminder = reminders.get(jj);
                    if(nextReminder.getFollowProcedure().equals(reminder.getFollowProcedure())) {
                        break;
                    }
                }

                for(int jj=ii-1; jj>=0; jj--) {
                    previousReminder = reminders.get(jj);
                    if(previousReminder.getFollowProcedure().equals(reminder.getFollowProcedure())) {
                        break;
                    }
                }

                for(Reminder reminderCompl : remindersCompleted) {
                    if(reminderCompl.getFollowProcedure().equals(reminder.getFollowProcedure())) {
                        if(reminderCompl.getCompleteDate().equals(reminder.getTargetDate())) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        } else if(previousReminder==null && reminderCompl.getCompleteDate().before(reminder.getTargetDate())) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        } else if(nextReminder==null && reminderCompl.getCompleteDate().after(reminder.getTargetDate())) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        } else if(previousReminder!=null && reminderCompl.getCompleteDate().before(reminder.getTargetDate()) && reminderCompl.getCompleteDate().after(findMidDate(previousReminder.getTargetDate(), reminder.getTargetDate()))) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        } else if(nextReminder!=null && reminderCompl.getCompleteDate().after(reminder.getTargetDate())&& reminderCompl.getCompleteDate().before(findMidDate(reminder.getTargetDate(), nextReminder.getTargetDate()))) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        }
                    }
                }
            }
        }

        //compare with indexDate to determine reminder/alert for each guideline
        List<Reminder> newReminders = new ArrayList<Reminder>();
        if(guidelines != null) {
            log.debug("Number of guidelines found = " + guidelines.size());
            for(Guideline guideline : guidelines) {
                Date targetDate = findNextTargetDate(reminders, guideline.getFollowupProcedure()); //completed reminders have been excluded from the next target date calculation

                Date alertDate = findAlertDate(pat, guideline.getFollowupProcedure(), targetDate); //based on default offset of 60 days and user response to earlier alert
                log.debug("guideline="+guideline.getFollowupProcedure() + ", years = " + guideline.getFollowupTimline());
                log.debug("targetDate=" + targetDate);
                log.debug("alertDate=" + alertDate);

                if(alertDate!= null && indexDate.after(alertDate)) {
                    log.debug("Alert is found for patient: " + pat + ", guideline=" + guideline + ", targetDate=" + targetDate + ", alertStartDate=" + alertDate);

                    //find Not Performed flag for this reminder
                    String notPerformedFlag = findAttribute(pat, guideline.getFollowupProcedure(), targetDate, "notPerformed");
                    if(notPerformedFlag != null && "Yes".equals(notPerformedFlag)) {
                        continue; //no need to alert
                    }

                    //find snooze date or schedule date for this reminder
                    Date ssDate = findSnoozeOrScheduleDate(pat, guideline.getFollowupProcedure(), targetDate);

                    if(ssDate == null || ssDate.before(new Date())) {
                        Reminder reminder = new Reminder();
                        reminder.setPatient(pat);
                        reminder.setFollowProcedure(guideline.getFollowupProcedure());
                        reminder.setTargetDate(targetDate);
                        newReminders.add(reminder);
                    }
                }
            }
        } else {
            log.debug("No guidelines are found!");
        }

        log.debug("Number of alerts found = " + (newReminders==null? 0: newReminders.size()) + " on index date " + indexDate);

        return newReminders;
    }

    /**
     * Find flagged reminders for a given patient for Calendar display
     * (flagged as Completed, Snoozed, Scheduled, Not Performed, or Skipped)
     *
     * Calendar display logic:
     *
     * find reminders by guideline -> find and add reminders by providers -> sort reminders by date ->
     * find and mark alerted reminders -> find and mark scheduled/snoozed reminders ->
     * find and mark not performed reminders -> find, match and mark completed reminders (overriding) ->
     * mark skipped reminders (remaining)
     *
     * @param pat
     */
    private List<Reminder>  findReminders(Patient pat) {
        //find all reminders recommended by guideline and patient's providers
        List<Reminder> reminders = findAllReminders(pat);

        if(reminders==null || reminders.isEmpty()) {
            return null;
        }

        //find completed reminders
        List<Reminder> remindersCompleted = getRemindersCompleted(pat);

        //***********************************************
        //mark each reminder as either completed, missed, scheduled, snoozed, near-future or far-future
        //based on follow-up care received, today's date, schedule response, and snooze response.
        //***********************************************

        //find alerted reminders
        Date today = new Date();
        List<Reminder> remindersAlerted = getReminders(pat, today);

        //mark alerted reminders
        if(remindersAlerted != null) {
            for(int ii = 0; ii< reminders.size(); ii++) {
                Reminder reminder = reminders.get(ii);
                for(Reminder alert : remindersAlerted) {
                    if(reminder.getFollowProcedure().equals(alert.getFollowProcedure()) && reminder.getTargetDate().equals(alert.getTargetDate())) {
                        reminder.setFlag(Reminder.FLAG_ALERTED);
                    }
                }
            }
        }

        //mark snoozed or scheduled reminders
        for(int ii = 0; ii< reminders.size(); ii++) {
            Reminder reminder = reminders.get(ii);
            Date scheduleDate = this.findSnoozeOrScheduleDate(pat, reminder.getFollowProcedure(), reminder.getTargetDate(), "scheduleDate");
            Date snoozeDate = this.findSnoozeOrScheduleDate(pat, reminder.getFollowProcedure(), reminder.getTargetDate(), "snoozeDate");
            if(scheduleDate!=null) {
                reminder.setFlag(Reminder.FLAG_SCHEDULED);
                reminder.setResponseDate(scheduleDate);
            } else if(snoozeDate!=null) {
                reminder.setFlag(Reminder.FLAG_SNOOZED);
                reminder.setResponseDate(snoozeDate);
            }
        }

        //mark Not-Performed reminders
        for(int ii = 0; ii< reminders.size(); ii++) {
            Reminder reminder = reminders.get(ii);
            String yesOrNo = this.findAttribute(pat, reminder.getFollowProcedure(), reminder.getTargetDate(), "notPerformed");
            if("Yes".equalsIgnoreCase(yesOrNo)) {
                reminder.setFlag(Reminder.FLAG_NOT_PERFORMED_YES);
            } else if("No".equalsIgnoreCase(yesOrNo)) {
                //reminder.setFlag(LafReminder.FLAG_NOT_PERFORMED_NO);
            }
        }

        //mark completed reminders (will take prevalence over other marks)
        if(remindersCompleted != null) {
            //loop through all reminders and find if that reminder is completed or not
            for(int ii = 0; ii< reminders.size(); ii++) {
                Reminder reminder = reminders.get(ii);
                Reminder nextReminder = null;
                Reminder previousReminder = null;

                //find next reminder of the same type
                for(int jj=ii+1; jj<reminders.size(); jj++) {
                    nextReminder = reminders.get(jj);
                    if(nextReminder.getFollowProcedure().equals(reminder.getFollowProcedure())) {
                        break;
                    }
                }

                //find previous reminder of the same type
                for(int jj=ii-1; jj>=0; jj--) {
                    previousReminder = reminders.get(jj);
                    if(previousReminder.getFollowProcedure().equals(reminder.getFollowProcedure())) {
                        break;
                    }
                }

                //find if this reminder can be marked as completed or not
                for(Reminder reminderCompl : remindersCompleted) {
                    if(reminderCompl.getFollowProcedure().equals(reminder.getFollowProcedure())) {
                        if(reminderCompl.getCompleteDate().equals(reminder.getTargetDate())) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        } else if(previousReminder==null && reminderCompl.getCompleteDate().before(reminder.getTargetDate())) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        } else if(nextReminder==null && reminderCompl.getCompleteDate().after(reminder.getTargetDate())) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        } else if(previousReminder!=null && reminderCompl.getCompleteDate().before(reminder.getTargetDate()) && reminderCompl.getCompleteDate().after(findMidDate(previousReminder.getTargetDate(), reminder.getTargetDate()))) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        } else if(nextReminder!=null && reminderCompl.getCompleteDate().after(reminder.getTargetDate())&& reminderCompl.getCompleteDate().before(findMidDate(reminder.getTargetDate(), nextReminder.getTargetDate()))) {
                            reminder.setFlag(Reminder.FLAG_COMPLETED);
                            reminder.setResponseDate(reminderCompl.getCompleteDate());
                            break;
                        }
                    }
                }
            }
        }

        //mark skipped reminders
        for(int ii = 0; ii< reminders.size(); ii++) {
            Reminder reminder = reminders.get(ii);
            if(reminder.getTargetDate().before(today) && reminder.getFlag() == null) {
                reminder.setFlag(Reminder.FLAG_SKIPPED);
            }
        }

        return reminders;
    }


    private String findAttribute(Patient patient, Concept careType, Date targetDate, String type) {
        Reminder reminder = this.dao.getReminder(patient, careType, targetDate);

        if(reminder==null || reminder.getResponseAttributes()==null) {
            return null;
        }
        String[] segments = reminder.getResponseAttributes().split(";");

        String ssValue = null;
        for(String segment : segments) {
            String ssType = null;
            if(segment != null) {
                String[] splits = segment.split("=");
                if(splits.length >=2) {
                    ssType = splits[0].trim();
                    if(type.equals(ssType)) {
                        ssValue = splits[1];
                    }
                }
            }
        }

        return ssValue;
    }

    /**
     *
     * Find all reminders including those by guideline and those by patient doctors
     *
     * find reminders by guideline -> find and add reminders by providers -> sort reminders by date
     *
     * @param pat given patient
     * @return all reminders sorted by target date
     */
    private List<Reminder>  findAllReminders(Patient pat) {
        //find cancer treatment summary encounter
    	/*
    	List<Encounter> encs = Context.getEncounterService().getEncountersByPatient(pat);
    	Integer encId = null;
    	Date encDate = null;
    	for(Encounter enc : encs) {
    		if(!enc.isVoided() && CANCER_TREATMENT_SUMMARY_ENCOUNTER.equals(enc.getEncounterType().getName())) {
    			if((encId == null || enc.getEncounterDatetime().after(encDate))) {
    				encId = enc.getId();
    				encDate = enc.getEncounterDatetime();
    				enc.getObs();
    			}
    		}
    	}
    	*/
        //find genetic_abnormality flag and answer
        //block any follow-up tests from appearing:
        //	1)       IF the patient answers YES to genetic abnormality, and
        //	2)       THEN answers FAP/HNPCC/or INFLAMMATORY BOWEL DISORDER
        //find genetic abnormality flag
        Concept cancerAbnormalityToldConcept = Context.getConceptService().getConcept(CANCER_ABNORMALITY_TOLD);
        Concept cancerAbnormalityToldYesConcept = Context.getConceptService().getConcept(CANCER_ABNORMALITY_TOLD_YES);
        Concept cancerAbnormalityConcept = Context.getConceptService().getConcept(CANCER_ABNORMALITY);
        Concept cancerAbnormalityFapConcept = Context.getConceptService().getConcept(CANCER_ABNORMALITY_FAP);
        Concept cancerAbnormalityHnpccConcept = Context.getConceptService().getConcept(CANCER_ABNORMALITY_HNPCC);
        Concept cancerAbnormalityInflbdConcept = Context.getConceptService().getConcept(CANCER_ABNORMALITY_INFLBD);

        Obs cancerAbnormalityToldObs = findLatest(Context.getObsService().getObservationsByPersonAndConcept(pat, cancerAbnormalityToldConcept));
        Concept cancerAbnormalityToldAns = (cancerAbnormalityToldObs==null? null : cancerAbnormalityToldObs.getValueCoded());
        if(cancerAbnormalityToldAns != null && cancerAbnormalityToldAns.equals(cancerAbnormalityToldYesConcept)) {
            Obs cancerAbnormalityObs = findLatest(Context.getObsService().getObservationsByPersonAndConcept(pat, cancerAbnormalityConcept));
            Concept cancerAbnormalityAns = (cancerAbnormalityObs==null? null : cancerAbnormalityObs.getValueCoded());
            if(cancerAbnormalityAns != null &&
                    (cancerAbnormalityAns.equals(cancerAbnormalityFapConcept) ||
                            cancerAbnormalityAns.equals(cancerAbnormalityHnpccConcept) ||
                            cancerAbnormalityAns.equals(cancerAbnormalityInflbdConcept))) {
                return null;
            }
        }

        //find surgery date
        Concept surgeryDateConcept = Context.getConceptService().getConcept(SURGERY_DATE);
        Obs surgeryDate = findLatest(Context.getObsService().getObservationsByPersonAndConcept(pat, surgeryDateConcept));
        Date surgDate = surgeryDate==null? null : surgeryDate.getValueDatetime();
        if(surgDate == null) {
            log.warn("No surgery is found for this patient: " + pat);
            return null;
        }

        //find rediation type - not used for this time
        Concept radiationTypeConcept = Context.getConceptService().getConcept(RADIATION_TYPE);
        Obs radiationType = findLatest(Context.getObsService().getObservationsByPersonAndConcept(pat, radiationTypeConcept));
        Concept radType = radiationType==null? null : radiationType.getValueCoded();

        //find cancer type
        Concept cancerTypeConcept = Context.getConceptService().getConcept(CANCER_TYPE);
        Obs cancerType = findLatest(Context.getObsService().getObservationsByPersonAndConcept(pat, cancerTypeConcept));
        Concept type = cancerType==null? null : cancerType.getValueCoded();
        //find cancer stage
        Concept cancerStageConcept = Context.getConceptService().getConcept(CANCER_STAGE);
        Obs cancerStage = findLatest(Context.getObsService().getObservationsByPersonAndConcept(pat, cancerStageConcept));
        Concept stage = cancerStage==null? null : cancerStage.getValueCoded();

        Set<Concept> conditionConcepts = new HashSet<>();
        conditionConcepts.add(type);
        conditionConcepts.add(stage);

        //find follow-up years
        List<Guideline> guidelines = Context.getService(GuidelineService.class).getGuidlinesByConditions(conditionConcepts);

        log.debug("Get guidelins: type=" + type + ", stage=" + stage + ", guidelines=" + guidelines);
        //create reminder entries
        List<Reminder> reminders = new ArrayList<>();
        if(guidelines != null) {
            for(Guideline guideline : guidelines) {
                Date[] dates = findTargetDates(surgDate, radType, guideline.getFollowupTimline());
                if(dates == null) {
                    continue;
                }
                for(Date dt: dates) {
                    Reminder reminder = new Reminder();
                    reminder.setPatient(pat);
                    reminder.setFollowProcedure(guideline.getFollowupProcedure());
                    reminder.setTargetDate(dt);
                    //update cancer_patient_reminder table
                    //reminderDao.saveLafReminder(reminder);
                    reminders.add(reminder);
                }
            }
        } else {
            log.error("Guideline is not found for cancer type:" + type + " and cancer stage: "+ stage);
        }

        //add follow-up care recommended by patient's personal provider
        List<Reminder> remindersByProvider = getRemindersByProvider(pat);
        if(remindersByProvider!=null && !remindersByProvider.isEmpty()) {
            reminders.addAll(remindersByProvider);
        }

        //sort guidelines by target date
        Collections.sort(reminders, Reminder.getDateComparator());
        return reminders;
    }

    private List<Reminder> getRemindersByProvider(Patient pat) {
        return dao.getRemindersByProvider(pat);
    }

    /**
     * Auto generated method comment
     *
     * @param reminders
     * @param followProcedure
     * @return
     */
    private Date findNextTargetDate(List<Reminder> reminders, Concept followProcedure) {

        Date today = new Date();

        Date nextTargetDate = null;
        Reminder refReminder1 = null;
        Reminder refReminder2 = null;
        for (Reminder reminder : reminders) {
            if (!followProcedure.equals(reminder.getFollowProcedure())) {
                continue;
            }

            refReminder2 = reminder;

            if (refReminder1 != null) {
                if (today.before(refReminder2.getTargetDate())) {
                    Date refDate12 = findMidDate(refReminder1.getTargetDate(), refReminder2.getTargetDate());

                    if (today.before(refDate12) && !Reminder.FLAG_COMPLETED.equals(refReminder1.getFlag())) {
                        //nextTargetDate = findDate(startDate, split2[ii]);
                        nextTargetDate = refReminder1.getTargetDate();
                    } else if (!Reminder.FLAG_COMPLETED.equals(refReminder2.getFlag())) {
                        nextTargetDate = refReminder2.getTargetDate();
                        ;
                    }

                    break;
                }
            }

            refReminder1 = refReminder2;
        }
        // TODO Auto-generated method stub
        return nextTargetDate;

    }
        /**
         * Auto generated method comment
         *
         * @param refDate1
         * @param refDate2
         * @return
         */
    private Date findMidDate(Date refDate1, Date refDate2) {
        // TODO Auto-generated method stub
        long diffDays = (refDate2.getTime() - refDate1.getTime())/(1000*60*60*24);

        Calendar cal = Calendar.getInstance();
        cal.setTime(refDate1);
        cal.add(Calendar.DATE, (int) diffDays/2);

        return cal.getTime();
    }

    @Override
    public List<Reminder> getRemindersCompleted(Patient pat) {
        List<Reminder> reminderList = dao.getRemindersCompleted(pat);
        return reminderList;
    }

    private Date[] findTargetDates(Date surgDate, Concept radiationType, String followYears) {
        String[] split1  = followYears.split(":");
        String[] split2 = split1[0].split(",");

        if(surgDate == null) {
            log.debug("No reminder will be generated because no surgery is found for this patient.");
            return null;
        }

        if(split1.length>=2 && "NO RADIATION".equals(split1[1])) {
            if(radiationType != null) {
                return null;
            }
        }

        Date startDate = surgDate;
        Date[] targetDates = new Date[split2.length];
        for(int ii=0; ii<split2.length; ii++) {
            targetDates[ii] = findDate(startDate, split2[ii]);
        }
        // TODO Auto-generated method stub
        return targetDates;
    }

    /**
     * Auto generated method comment

     * @param startDate
     * @param
     * @return
     */
    private Date findDate(Date startDate, String yearsAfter) {
        float yrs = Float.parseFloat(yearsAfter);
        int months = (int)(yrs * 12.0);
        Calendar cal = Calendar.getInstance();
        cal.setTime(startDate);
        cal.add(Calendar.MONTH, months);

        // TODO Auto-generated method stub
        return cal.getTime();
    }

    /**
     * Auto generated method comment
     * @param careType
     * @param pat
     *
     * @param targetDate
     * @return
     */
    private Date findAlertDate(Patient pat, Concept careType, Date targetDate) {
        // TODO Auto-generated method stub
        if(targetDate==null) return null;

        Calendar cal = Calendar.getInstance();
        cal.setTime(targetDate);
        cal.add(Calendar.DATE, -ALERT_DAYS);

        log.debug("alertDate=" + cal.getTime() + " based on offset of " + ALERT_DAYS + "days");

        return cal.getTime();
    }

    /**
     * Auto generated method comment
     *
     * @param
     * @param targetDate
     * @param
     * @return
     */
    private Date findSnoozeOrScheduleDate(Patient patient, Concept careType, Date targetDate) {
        Reminder reminder = this.dao.getReminder(patient, careType, targetDate);

        Date ssDate = null;
        String ssType = null;
        if(reminder != null) {
            String[] splits = reminder.getResponseAttributes().split("=");
            if(splits.length >=2) {
                ssType = splits[0];
                if("snoozeDate".equals(ssType) || "scheduleDate".equals(ssType)) {
                    try {
                        ssDate = Context.getDateFormat().parse(splits[1]);
                    } catch (ParseException e) {
                        log.error("Bad date format: ssType=" + ssType + ", ssDate=" + ssDate);
                    }
                    log.debug("This reminder is snoozed or scheduled: ssType=" + ssType + ", ssDate=" + ssDate);
                }
            }
        }

        //void this date if it is older than today's date
        //if(ssDate!=null && ssDate.before(new Date())) {
        //	ssDate = null;
        //}

        return ssDate;
    }

    private Date findSnoozeOrScheduleDate(Patient patient, Concept careType, Date targetDate, String type) {
        Reminder reminder = this.dao.getReminder(patient, careType, targetDate);

        Date ssDate = null;
        String ssType = null;
        if(reminder != null) {
            String[] splits = reminder.getResponseAttributes().split("=");
            if(splits.length >=2) {
                ssType = splits[0];
                if(type.equals(ssType)) {
                    try {
                        ssDate = Context.getDateFormat().parse(splits[1]);
                    } catch (ParseException e) {
                        log.error("Bad date format: ssType=" + ssType + ", ssDate=" + ssDate);
                    }
                    log.debug("This reminder is snoozed or scheduled: ssType=" + ssType + ", ssDate=" + ssDate);
                }
            }
        }

        //void this date if it is older than today's date
        if(ssDate!=null && ssDate.before(new Date())) {
            ssDate = null;
        }

        return ssDate;
    }

    /**
     *
     *
     * @param
     * @return
     */
    private Obs findLatest(List<Obs> observations) {
        Obs latest = null;

        if(observations != null) {
            for (Obs obs : observations) {
                if(obs != null && !obs.isVoided()) {
                    if(latest == null || latest.getDateCreated().before(obs.getDateCreated())) {
                        latest = obs;
                    }

                }
            }
        }

        return latest;
    }

}


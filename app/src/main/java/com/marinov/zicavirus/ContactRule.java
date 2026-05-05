package com.marinov.zicavirus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Representa uma regra de bloqueio para um contato.
 */
public class ContactRule {

    public String contactId;
    public String contactName;
    public List<String> phoneNumbers = new ArrayList<>();

    /** Número máximo de ligações antes da penalidade (1-10) */
    public int maxCalls = 3;

    /** Ação: "block" */
    public String action = "block";

    /** Duração da penalidade em minutos */
    public int penaltyMinutes = 60;

    /** Epoch millis até quando o contato está bloqueado (0 = não bloqueado) */
    public long blockedUntil = 0;

    /** Se true, chamadas fora da janela de horário são bloqueadas */
    public boolean timeWindowEnabled = false;

    /** Horário de início da janela permitida, formato "HH:mm" */
    public String timeWindowStart = "08:00";

    /** Horário de fim da janela permitida, formato "HH:mm" */
    public String timeWindowEnd = "22:00";

    /** Período de reset da contagem em minutos (padrão: 2880 = 48h) */
    public int resetWindowMinutes = 2880;

    // -------------------------------------------------------------------------
    // Serialização JSON
    // -------------------------------------------------------------------------

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("contactId", contactId);
        obj.put("contactName", contactName);

        JSONArray nums = new JSONArray();
        for (String n : phoneNumbers) nums.put(n);
        obj.put("phoneNumbers", nums);

        obj.put("maxCalls", maxCalls);
        obj.put("action", action);
        obj.put("penaltyMinutes", penaltyMinutes);
        obj.put("blockedUntil", blockedUntil);
        obj.put("timeWindowEnabled", timeWindowEnabled);
        obj.put("timeWindowStart", timeWindowStart);
        obj.put("timeWindowEnd", timeWindowEnd);
        obj.put("resetWindowMinutes", resetWindowMinutes);
        return obj;
    }

    public static ContactRule fromJson(JSONObject obj) throws JSONException {
        ContactRule rule = new ContactRule();
        rule.contactId = obj.getString("contactId");
        rule.contactName = obj.getString("contactName");

        JSONArray nums = obj.getJSONArray("phoneNumbers");
        for (int i = 0; i < nums.length(); i++) {
            rule.phoneNumbers.add(nums.getString(i));
        }

        rule.maxCalls = obj.getInt("maxCalls");
        rule.action = obj.optString("action", "block");
        rule.penaltyMinutes = obj.getInt("penaltyMinutes");
        rule.blockedUntil = obj.optLong("blockedUntil", 0);
        rule.timeWindowEnabled = obj.optBoolean("timeWindowEnabled", false);
        rule.timeWindowStart = obj.optString("timeWindowStart", "08:00");
        rule.timeWindowEnd = obj.optString("timeWindowEnd", "22:00");
        rule.resetWindowMinutes = obj.optInt("resetWindowMinutes", 2880);
        return rule;
    }
}
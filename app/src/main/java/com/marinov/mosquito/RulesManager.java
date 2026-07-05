package com.marinov.mosquito;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Gerencia as regras de bloqueio persistidas em SharedPreferences como JSON.
 */
public class RulesManager {

    private static final String PREFS_NAME = "zicavirus_rules";
    private static final String KEY_RULES  = "rules_json";

    // -------------------------------------------------------------------------
    // Leitura
    // -------------------------------------------------------------------------

    public static List<ContactRule> getRules(Context ctx) {
        List<ContactRule> list = new ArrayList<>();
        String json = prefs(ctx).getString(KEY_RULES, null);
        if (json == null) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(ContactRule.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Escrita
    // -------------------------------------------------------------------------

    public static void saveRules(Context ctx, List<ContactRule> rules) {
        JSONArray arr = new JSONArray();
        for (ContactRule rule : rules) {
            try { arr.put(rule.toJson()); } catch (JSONException ignored) {}
        }
        prefs(ctx).edit().putString(KEY_RULES, arr.toString()).apply();
    }

    public static void addRule(Context ctx, ContactRule rule) {
        List<ContactRule> rules = getRules(ctx);
        // Remove se já existe (substituir)
        rules.removeIf(r -> r.contactId.equals(rule.contactId));
        rules.add(rule);
        saveRules(ctx, rules);
    }

    public static void removeRule(Context ctx, String contactId) {
        List<ContactRule> rules = getRules(ctx);
        rules.removeIf(r -> r.contactId.equals(contactId));
        saveRules(ctx, rules);
    }

    public static void updateRule(Context ctx, ContactRule updated) {
        List<ContactRule> rules = getRules(ctx);
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).contactId.equals(updated.contactId)) {
                rules.set(i, updated);
                break;
            }
        }
        saveRules(ctx, rules);
    }

    // -------------------------------------------------------------------------
    // Busca por número de telefone
    // -------------------------------------------------------------------------

    /**
     * Retorna a regra cujo contato possui o número informado,
     * ou null se não houver regra para esse número.
     */
    public static ContactRule findRuleForNumber(Context ctx, String rawNumber) {
        if (rawNumber == null) return null;
        String normalized = normalize(rawNumber);
        for (ContactRule rule : getRules(ctx)) {
            for (String num : rule.phoneNumbers) {
                if (normalize(num).equals(normalized)) return rule;
            }
        }
        return null;
    }

    /**
     * Retorna a regra de um contactId específico.
     */
    public static ContactRule findRuleForContact(Context ctx, String contactId) {
        if (contactId == null) return null;
        for (ContactRule rule : getRules(ctx)) {
            if (rule.contactId.equals(contactId)) return rule;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Auxiliar
    // -------------------------------------------------------------------------

    /** Remove tudo exceto dígitos para comparação. */
    private static String normalize(String number) {
        return number.replaceAll("[^0-9]", "");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
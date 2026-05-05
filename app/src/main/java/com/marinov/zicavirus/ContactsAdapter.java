package com.marinov.zicavirus;

import android.app.TimePickerDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ContactsAdapter extends RecyclerView.Adapter<ContactsAdapter.ViewHolder> {

    public interface OnRemoveListener {
        void onRemove(int position, ContactRule rule);
    }

    private final Context context;
    private final List<ContactRule> rules;
    private OnRemoveListener removeListener;

    public ContactsAdapter(Context context, List<ContactRule> rules) {
        this.context = context;
        this.rules   = rules != null ? rules : new ArrayList<>();
    }

    public void setOnRemoveListener(OnRemoveListener listener) {
        this.removeListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact_card, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContactRule rule = rules.get(position);
        holder.bind(rule);
    }

    @Override
    public int getItemCount() {
        return rules.size();
    }

    public void addRule(ContactRule rule) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).contactId.equals(rule.contactId)) {
                rules.set(i, rule);
                notifyItemChanged(i);
                return;
            }
        }
        rules.add(rule);
        notifyItemInserted(rules.size() - 1);
    }

    public void removeAt(int position) {
        rules.remove(position);
        notifyItemRemoved(position);
    }

    public void restoreRule(int position, ContactRule rule) {
        rules.add(position, rule);
        notifyItemInserted(position);
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        TextView       tvContactName, tvPhoneNumbers;
        ImageButton    btnRemove;
        Spinner        spinnerMaxCalls, spinnerDuration, spinnerUnit;
        SwitchMaterial switchTimeWindow;
        LinearLayout   layoutTimeWindow;
        TextView       tvTimeStart, tvTimeEnd;

        boolean binding = false;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvContactName    = itemView.findViewById(R.id.tvContactName);
            tvPhoneNumbers   = itemView.findViewById(R.id.tvPhoneNumbers);
            btnRemove        = itemView.findViewById(R.id.btnRemove);
            spinnerMaxCalls  = itemView.findViewById(R.id.spinnerMaxCalls);
            spinnerDuration  = itemView.findViewById(R.id.spinnerDuration);
            spinnerUnit      = itemView.findViewById(R.id.spinnerUnit);
            switchTimeWindow = itemView.findViewById(R.id.switchTimeWindow);
            layoutTimeWindow = itemView.findViewById(R.id.layoutTimeWindow);
            tvTimeStart      = itemView.findViewById(R.id.tvTimeStart);
            tvTimeEnd        = itemView.findViewById(R.id.tvTimeEnd);
        }

        void bind(ContactRule rule) {
            binding = true;

            tvContactName.setText(rule.contactName);
            StringBuilder nums = new StringBuilder();
            for (String n : rule.phoneNumbers) {
                if (nums.length() > 0) nums.append("\n");
                nums.append(n);
            }
            tvPhoneNumbers.setText(nums.toString());

            // Spinner de quantidade (1-10)
            String[] qtd = {"1","2","3","4","5","6","7","8","9","10"};
            spinnerMaxCalls.setAdapter(simpleAdapter(qtd));
            spinnerMaxCalls.setSelection(Math.max(0, rule.maxCalls - 1));

            // Unidade de tempo
            String[] units = {
                    context.getString(R.string.unit_minutes),
                    context.getString(R.string.unit_hours)
            };
            spinnerUnit.setAdapter(simpleAdapter(units));

            boolean isHours = rule.penaltyMinutes > 0 && rule.penaltyMinutes % 60 == 0 && rule.penaltyMinutes / 60 <= 24;
            int unitIdx = isHours ? 1 : 0;
            spinnerUnit.setSelection(unitIdx);

            updateDurationSpinner(rule, unitIdx);

            switchTimeWindow.setChecked(rule.timeWindowEnabled);
            layoutTimeWindow.setVisibility(rule.timeWindowEnabled ? View.VISIBLE : View.GONE);
            tvTimeStart.setText(rule.timeWindowStart);
            tvTimeEnd.setText(rule.timeWindowEnd);

            binding = false;

            // Listeners
            spinnerMaxCalls.setOnItemSelectedListener(new SimpleItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (binding) return;
                    int idx = getAdapterPosition();
                    if (idx < 0) return;
                    ContactRule r = rules.get(idx);
                    r.maxCalls = pos + 1;
                    RulesManager.updateRule(context, r);
                }
            });

            spinnerUnit.setOnItemSelectedListener(new SimpleItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (binding) return;
                    int idx = getAdapterPosition();
                    if (idx < 0) return;
                    ContactRule r = rules.get(idx);
                    updateDurationSpinner(r, pos);
                }
            });

            spinnerDuration.setOnItemSelectedListener(new SimpleItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (binding) return;
                    int idx = getAdapterPosition();
                    if (idx < 0) return;
                    ContactRule r = rules.get(idx);
                    boolean hrs = spinnerUnit.getSelectedItemPosition() == 1;
                    r.penaltyMinutes = hrs ? (pos + 1) * 60 : (pos + 1);
                    RulesManager.updateRule(context, r);
                }
            });

            switchTimeWindow.setOnCheckedChangeListener((btn, checked) -> {
                if (binding) return;
                int idx = getAdapterPosition();
                if (idx < 0) return;
                ContactRule r = rules.get(idx);
                r.timeWindowEnabled = checked;
                layoutTimeWindow.setVisibility(checked ? View.VISIBLE : View.GONE);
                RulesManager.updateRule(context, r);
            });

            tvTimeStart.setOnClickListener(v -> {
                int idx = getAdapterPosition();
                if (idx < 0) return;
                ContactRule r = rules.get(idx);
                int[] parts = parseTime(r.timeWindowStart);
                new TimePickerDialog(context, (tp, h, m) -> {
                    r.timeWindowStart = String.format(Locale.getDefault(), "%02d:%02d", h, m);
                    tvTimeStart.setText(r.timeWindowStart);
                    RulesManager.updateRule(context, r);
                }, parts[0], parts[1], true).show();
            });

            tvTimeEnd.setOnClickListener(v -> {
                int idx = getAdapterPosition();
                if (idx < 0) return;
                ContactRule r = rules.get(idx);
                int[] parts = parseTime(r.timeWindowEnd);
                new TimePickerDialog(context, (tp, h, m) -> {
                    r.timeWindowEnd = String.format(Locale.getDefault(), "%02d:%02d", h, m);
                    tvTimeEnd.setText(r.timeWindowEnd);
                    RulesManager.updateRule(context, r);
                }, parts[0], parts[1], true).show();
            });

            btnRemove.setOnClickListener(v -> {
                int idx = getAdapterPosition();
                if (idx < 0) return;
                if (removeListener != null) removeListener.onRemove(idx, rules.get(idx));
            });
        }

        private void updateDurationSpinner(ContactRule rule, int unitIndex) {
            boolean isHours = unitIndex == 1;
            int max = isHours ? 24 : 59;
            String[] values = new String[max];
            for (int i = 0; i < max; i++) values[i] = String.valueOf(i + 1);
            spinnerDuration.setAdapter(simpleAdapter(values));

            int currentValue;
            if (isHours) {
                currentValue = (rule.penaltyMinutes / 60) - 1;
            } else {
                currentValue = rule.penaltyMinutes - 1;
            }
            int selection = Math.max(0, Math.min(currentValue, max - 1));
            spinnerDuration.setSelection(selection);
        }

        private ArrayAdapter<String> simpleAdapter(String[] items) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    context,
                    R.layout.spinner_item,
                    items);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            return adapter;
        }

        private int[] parseTime(String hhmm) {
            try {
                String[] parts = hhmm.split(":");
                return new int[]{ Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
            } catch (Exception e) {
                return new int[]{ 8, 0 };
            }
        }
    }

    private abstract static class SimpleItemSelectedListener
            implements AdapterView.OnItemSelectedListener {
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    }
}
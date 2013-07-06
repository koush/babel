package com.koushikdutta.babel;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.ListView;
import android.widget.Switch;

public class Babel extends Activity {
    class AccountAdapter extends ArrayAdapter<Account> {
        AccountAdapter() {
            super(Babel.this, android.R.layout.simple_list_item_single_choice);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);

            CheckedTextView tv = (CheckedTextView) view.findViewById(android.R.id.text1);
            Account account = getItem(position);
            tv.setText(account.name);

            return view;
        }
    }

    Account NULL;

    ListView lv;
    AccountAdapter accountAdapter;
    SharedPreferences settings;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        accountAdapter = new AccountAdapter();
        settings = getSharedPreferences("settings", MODE_PRIVATE);

        lv = (ListView) findViewById(R.id.list);
        lv.setAdapter(accountAdapter = new AccountAdapter());

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Account account = accountAdapter.getItem(position);
                if (account == NULL) {
                    settings.edit().remove("account").remove("rnrse").commit();
                    return;
                }

                lv.clearChoices();
                lv.requestLayout();
                getToken(account, position);
            }
        });

        String selectedAccount = settings.getString("account", null);

        NULL = new Account(getString(R.string.disable), "com.google");
        accountAdapter.add(NULL);
        int selected = 0;
        for (Account account : AccountManager.get(this).getAccountsByType("com.google")) {
            if (account.name.equals(selectedAccount))
                selected = accountAdapter.getCount();
            accountAdapter.add(account);
        }

        lv.setItemChecked(selected, true);
        lv.requestLayout();

        startService(new Intent(this, BabelService.class));
    }

    void startAccessibility() {
        Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivityForResult(intent, 0);
    }

    void getToken(final Account account, final int position) {
        AccountManager am = AccountManager.get(this);
        if (am == null)
            return;
        am.getAuthToken(account, "grandcentral", null, this, new AccountManagerCallback<Bundle>() {
            @Override
            public void run(AccountManagerFuture<Bundle> future) {
                try {
                    Bundle bundle = future.getResult();
                    final String authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);
                    settings.edit()
                    .putString("account", account.name)
                    .commit();

                    lv.setItemChecked(position, true);
                    lv.requestLayout();
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }, new Handler());
    }
}

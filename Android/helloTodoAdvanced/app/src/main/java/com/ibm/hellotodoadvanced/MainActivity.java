package com.ibm.hellotodoadvanced;


/**
 * Copyright 2016 IBM Corp. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.ibm.mobilefirstplatform.clientsdk.android.core.api.Request;
import com.ibm.mobilefirstplatform.clientsdk.android.core.api.BMSClient;
import com.ibm.mobilefirstplatform.clientsdk.android.core.api.Response;
import com.ibm.mobilefirstplatform.clientsdk.android.core.api.ResponseListener;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPush;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPushException;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPushNotificationListener;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPushResponseListener;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPSimplePushNotification;
import com.ibm.mobilefirstplatform.clientsdk.android.security.api.AuthorizationManager;
import com.ibm.mobilefirstplatform.clientsdk.android.security.facebookauthentication.FacebookAuthenticationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The {@code MainActivity} is the primary visual activity shown when the app is being interacted with.
 */
public class MainActivity extends Activity implements ResponseListener {

    private static final String TAG = MainActivity.class.getSimpleName();

    private ListView mListView; // Main ListView
    private List<TodoItem> mTodoItemList; // The list of TodoItems
    private TodoItemAdapter mTodoItemAdapter; // Adapter for bridging the list of TodoItems with the ListView

    private SwipeRefreshLayout mSwipeLayout; // Swipe refresh to update local app if backend has changed

    private BMSClient client; // IBM Mobile First Client SDK

    private MFPPush push; // Push client
    private MFPPushNotificationListener notificationListener; // Notification listener to handle a push sent to the phone


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        client = BMSClient.getInstance();
        try {
            //initialize SDK with IBM Bluemix application ID and route
            //You can find your backendRoute and backendGUID in the Mobile Options section on top of your Bluemix application dashboard
            //TODO: Please replace <APPLICATION_ROUTE> with a valid ApplicationRoute and <APPLICATION_ID> with a valid ApplicationId
            client.initialize(this, "<APPLICATION_ROUTE>", "<APPLICATION_ID>");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        FacebookAuthenticationManager.getInstance().register(this);

        AuthorizationManager.getInstance().obtainAuthorizationHeader(this, this);

        initListView();
        initSwipeRefresh();
        initPush();
    }

    @Override
    public void onResume() {
        super.onResume();

        AuthorizationManager.getInstance().obtainAuthorizationHeader(this, this);

        // If the device has been registered previously, ensure the client sdk is still using the notification listener when app is resumed
        if (push != null) {
            push.listen(notificationListener);
        }
    }


    /**
     * Handles response after attempting Facebook
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        FacebookAuthenticationManager.getInstance().onActivityResultCalled(requestCode, resultCode, data);

    }

    //ResponseListener for FB login
    @Override
    public void onSuccess(Response response) {
        Log.i(TAG, "Logged into Facebook successfully");

        loadList();
    }

    @Override
    public void onFailure(Response response, Throwable t, JSONObject extendedInfo) {
        if(response != null) {
            Log.e(TAG, "Failed to login to Facebook: Response: " + response.getResponseText());
        }if (t != null){
            Log.e(TAG, "Failed to login to Facebook: Throwable: " + t.getMessage());
        }if (extendedInfo != null){
            Log.e(TAG, "Failed to login to Facebook: ExtendedInfo: " + extendedInfo.toString());
        }else{
            Log.e(TAG, "Failed to login to Facebook, unknown reason");
        }
    }

    private void initListView() {
        // Get MainActivity's ListView
        mListView = (ListView) findViewById(R.id.listView);

        // Init array to hold TodoItems
        mTodoItemList = new ArrayList<>();

        // Create and set ListView adapter for displaying TodoItems
        mTodoItemAdapter = new TodoItemAdapter(getBaseContext(), mTodoItemList);
        mListView.setAdapter(mTodoItemAdapter);

        // Set long click listener for deleting TodoItems
        mListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {

                // Grab TodoItem to delete from current showing list
                TodoItem todoItem = mTodoItemList.get(position);

                // Grab TodoItem id number and append to the DELETE rest request using the IBM Mobile First Client SDK
                String todoId = Integer.toString(todoItem.idNumber);
                Request request = new Request(client.getBluemixAppRoute() + "/api/Items/" + todoId, Request.DELETE);

                // Send the request and use the response listener to react
                request.send(getApplicationContext(), new ResponseListener() {
                    // Update the list if successful
                    @Override
                    public void onSuccess(Response response) {
                        Log.i(TAG, "Item  deleted successfully");

                        loadList();
                    }

                    // If the request fails, log the errors
                    @Override
                    public void onFailure(Response response, Throwable t, JSONObject extendedInfo) {

                        if (t != null) {
                            Log.e(TAG, "deleteItem failed with error: " + t.getLocalizedMessage());
                        } else if (response != null) {
                            Log.e(TAG, "deleteItem failed with error: " + response.toString());
                        } else if (extendedInfo != null) {
                            Log.e(TAG, "deleteItem failed with error: " + extendedInfo.toString());
                        } else {
                            Log.e(TAG, "deleteItem failed with error: Reason Unkown");
                        }

                    }
                });

                return true;
            }
        });
    }

    /**
     * Enables swipe down refresh for the list
     */
    private void initSwipeRefresh() {

        mSwipeLayout = (SwipeRefreshLayout) findViewById(R.id.swipe_container);

        // Set swipe refresh listener to update the local list on pull down
        mSwipeLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                loadList();
            }
        });
    }

    /**
     * Uses IBM Mobile First SDK to get the TodoItems from Bluemix and updates the local list
     */
    private void loadList() {

        // Identify and send GET Request with response listener
        Request request = new Request(client.getBluemixAppRoute()+"/api/Items", Request.GET);
        request.send(getApplicationContext(), new ResponseListener() {
            // Loop through JSON response and create local TodoItems if successful
            @Override
            public void onSuccess(Response response) {
                if (response.getStatus() != 200) {
                    Log.e(TAG, "Error pulling items from Bluemix: " + response.toString());
                } else {

                    try {

                        mTodoItemList.clear();

                        JSONArray jsonArray = new JSONArray(response.getResponseText());

                        for (int i = 0; i < jsonArray.length(); i++) {
                            JSONObject temp = jsonArray.getJSONObject(i);
                            TodoItem tempTodo = new TodoItem();

                            tempTodo.idNumber = temp.getInt("id");
                            tempTodo.text = temp.getString("text");
                            tempTodo.isDone = temp.getBoolean("isDone");

                            mTodoItemList.add(tempTodo);
                        }

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mTodoItemAdapter.notifyDataSetChanged();

                                if (mSwipeLayout.isRefreshing()) {
                                    mSwipeLayout.setRefreshing(false);
                                }
                            }
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Error reading response JSON: " + e.getLocalizedMessage());
                    }
                }
            }

            // Log Errors on failure
            @Override
            public void onFailure(Response response, Throwable throwable, JSONObject jsonObject) {
                if (throwable != null) {
                    Log.e(TAG, "Failed sending request to Bluemix: " + throwable.getLocalizedMessage());
                }if (response != null) {
                    Log.e(TAG, "Failed sending request to Bluemix: " + response.toString());
                }if (jsonObject != null) {
                    Log.e(TAG, "Failed sending request to Bluemix: " + jsonObject.toString());
                } else {
                    Log.e(TAG, "Failed sending request to Bluemix: Reason Unkown");
                }
            }
        });
    }

    private void initPush(){
        // Initialize Push client
        push = MFPPush.getInstance();

        push.initialize(this);

        // Create notification listener and enable pop up notification when a message is received
        notificationListener = new MFPPushNotificationListener() {
            @Override
            public void onReceive(final MFPSimplePushNotification message) {
                Log.i(TAG, "Received a Push Notification: " + message.toString());
                runOnUiThread(new Runnable() {
                    public void run() {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Received a Push Notification")
                                .setMessage(message.getAlert())
                                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                    }
                                })
                                .show();
                    }
                });
            }
        };

        Log.i(TAG, "Registering for notifications");

        // Creates response listener to handle the response when a device is registered.
        MFPPushResponseListener registrationResponselistener = new MFPPushResponseListener<String>() {
            @Override
            public void onSuccess(String s) {
                Log.i(TAG, "Successfully registered for push notifications");
                push.listen(notificationListener);
            }

            @Override
            public void onFailure(MFPPushException e) {
                Log.e(TAG,"Failed to register for notifications: " + e.getErrorMessage());
                push = null;
            }
        };

        // Attempt to register device using response listener created above
        push.register(registrationResponselistener);
    }

    // If the device has been registered previously, hold push notifications when the app is paused
    @Override
    protected void onPause() {
        super.onPause();

        if (push != null) {
            push.hold();
        }
    }

    /**
     * Launches a dialog for adding a new TodoItem. Called when plus button is tapped.
     *
     * @param view The plus button that is tapped.
     */
    public void addTodo(View view) {

        final Dialog addDialog = new Dialog(this);

        addDialog.setContentView(R.layout.add_edit_dialog);
        addDialog.setTitle("Add Todo");
        TextView textView = (TextView) addDialog.findViewById(android.R.id.title);
        if (textView != null) {
            textView.setGravity(Gravity.CENTER);
        }

        addDialog.setCancelable(true);
        Button add = (Button) addDialog.findViewById(R.id.Add);
        addDialog.show();

        // When done is pressed, send POST request to create TodoItem on Bluemix
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                EditText itemToAdd = (EditText) addDialog.findViewById(R.id.todo);
                final String name = itemToAdd.getText().toString();
                // If text was added, continue with normal operations
                if (!name.isEmpty()) {

                    // Create JSON for new TodoItem, id should be 0 for new items
                    String json = "{\"text\":\"" + name + "\",\"isDone\":false,\"id\":0}";

                    // Create POST request with IBM Mobile First SDK and set HTTP headers so Bluemix knows what to expect in the request
                    Request request = new Request(client.getBluemixAppRoute() + "/api/Items", Request.POST);

                    HashMap headers = new HashMap();
                    List<String> cType = new ArrayList<>();
                    cType.add("application/json");
                    List<String> accept = new ArrayList<>();
                    accept.add("Application/json");

                    headers.put("Content-Type", cType);
                    headers.put("Accept", accept);

                    request.setHeaders(headers);

                    request.send(getApplicationContext(), json, new ResponseListener() {
                        // On success, update local list with new TodoItem
                        @Override
                        public void onSuccess(Response response) {
                            Log.i(TAG, "Item created successfully");

                            loadList();
                        }

                        // On failure, log errors
                        @Override
                        public void onFailure(Response response, Throwable t, JSONObject extendedInfo) {
                            if (response != null) {
                                Log.e(TAG, "createItem failed with error: " + response.getResponseText());
                            }
                            if (t != null) {
                                Log.e(TAG, "createItem failed with error: " + t.getLocalizedMessage(), t);
                            }
                            if (extendedInfo != null) {
                                Log.e(TAG, "createItem failed with error: " + extendedInfo.toString());
                            }

                        }
                    });
                }

                // Kill dialog when finished, or if no text was added
                addDialog.dismiss();
            }
        });
    }

    /**
     * Launches a dialog for updating the TodoItem name. Called when the list item is tapped.
     *
     * @param view The TodoItem that is tapped.
     */
    public void editTodoName(View view) {
        // Gets position in list view of tapped item
        final Integer pos = mListView.getPositionForView(view);
        final Dialog addDialog = new Dialog(this);

        addDialog.setContentView(R.layout.add_edit_dialog);
        addDialog.setTitle("Edit Todo");
        TextView textView = (TextView) addDialog.findViewById(android.R.id.title);
        if (textView != null) {
            textView.setGravity(Gravity.CENTER);
        }
        addDialog.setCancelable(true);
        EditText et = (EditText) addDialog.findViewById(R.id.todo);

        final String name = mTodoItemList.get(pos).text;
        final boolean isDone = mTodoItemList.get(pos).isDone;
        final int id = mTodoItemList.get(pos).idNumber;
        et.setText(name);

        Button addDone = (Button) addDialog.findViewById(R.id.Add);
        addDialog.show();

        // When done is pressed, send PUT request to update TodoItem on Bluemix
        addDone.setOnClickListener(new View.OnClickListener() {
            // Save text inputted when done is tapped
            @Override
            public void onClick(View view) {
                EditText editedText = (EditText) addDialog.findViewById(R.id.todo);

                String newName = editedText.getText().toString();

                // If new text is not empty, create JSON with updated info and send PUT request
                if (!newName.isEmpty()) {
                    String json = "{\"text\":\"" + newName + "\",\"isDone\":" + isDone + ",\"id\":" + id + "}";

                    // Create PUT REST request using the IBM Mobile First SDK and set HTTP headers so Bluemix knows what to expect in the request
                    Request request = new Request(client.getBluemixAppRoute() + "/api/Items", Request.PUT);

                    HashMap headers = new HashMap();
                    List<String> cType = new ArrayList<>();
                    cType.add("application/json");
                    List<String> accept = new ArrayList<>();
                    accept.add("Application/json");

                    headers.put("Content-Type", cType);
                    headers.put("Accept", accept);

                    request.setHeaders(headers);

                    request.send(getApplicationContext(), json, new ResponseListener() {
                        // On success, update local list with updated TodoItem
                        @Override
                        public void onSuccess(Response response) {
                            Log.i("MainActivity", "Item updated successfully");

                            loadList();
                        }

                        // On failure, log errors
                        @Override
                        public void onFailure(Response response, Throwable t, JSONObject extendedInfo) {
                            if (response != null) {
                                Log.e("MainActivity", "updateItem failed with error: " + response.getResponseText());
                            }
                            if (t != null) {
                                Log.e("MainActivity", "updateItem failed with error: " + t.getLocalizedMessage(), t);
                            }
                            if (extendedInfo != null) {
                                Log.e("MainActivity", "updateItem failed with error: " + extendedInfo.toString());
                            }

                        }
                    });

                }
                addDialog.dismiss();
            }
        });
    }

    /**
     * Changes completed image and flips TodoItem isDone boolean value. Same request as editTodoName.
     *
     * @param view The TodoItem that has been tapped.
     */
    public void isDoneToggle(View view) {
        Integer pos = mListView.getPositionForView(view);
        final TodoItem todoItem = mTodoItemList.get(pos);

        final boolean isDone = !todoItem.isDone;

        String json = "{\"text\":\"" + todoItem.text + "\",\"isDone\":" + isDone + ",\"id\":" + todoItem.idNumber + "}";

        // Create PUT REST request using the IBM Mobile First SDK and set HTTP headers so Bluemix knows what to expect in the request
        Request request = new Request(client.getBluemixAppRoute() + "/api/Items", Request.PUT);

        HashMap headers = new HashMap();

        List<String> cType = new ArrayList<>();
        cType.add("application/json");
        List<String> accept = new ArrayList<>();
        accept.add("Application/json");

        headers.put("Content-Type", cType);
        headers.put("Accept", accept);

        request.setHeaders(headers);

        request.send(getApplicationContext(), json, new ResponseListener() {
            // On success, update local list with updated TodoItem
            @Override
            public void onSuccess(Response response) {
                Log.i(TAG, "Item completeness updated successfully");

                loadList();

                if (isDone) {
                    notifyAllDevices(todoItem.text);
                }
            }

            // On failure, log errors
            @Override
            public void onFailure(Response response, Throwable t, JSONObject extendedInfo) {
                if (response != null) {
                    Log.e(TAG, "isDoneToggle failed with error: " + response.getResponseText());
                }
                if (t != null) {
                    Log.e(TAG, "isDoneToggle failed with error: " + t.getLocalizedMessage(), t);
                }
                if (extendedInfo != null) {
                    Log.e(TAG, "isDoneToggle failed with error: " + extendedInfo.toString());
                }
            }
        });

    }

    public void notifyAllDevices(String completedItem) {
        Request request = new Request(client.getBluemixAppRoute() + "/notifyAllDevices", Request.POST);

        String json = "{\"text\":\"" + completedItem + "\"}";

        HashMap headers = new HashMap();

        List<String> cType = new ArrayList<>();
        cType.add("application/json");
        List<String> accept = new ArrayList<>();
        accept.add("Application/json");

        headers.put("Content-Type", cType);
        headers.put("Accept", accept);

        request.setHeaders(headers);

        request.send(getApplicationContext(), json, new ResponseListener() {
            // On success, update local list with updated TodoItem
            @Override
            public void onSuccess(Response response) {
                Log.i(TAG, "All registered devices notified successfully");
            }

            // On failure, log errors
            @Override
            public void onFailure(Response response, Throwable t, JSONObject extendedInfo) {
                if (response != null) {
                    Log.e(TAG, "Notify all devices failed with response: " + response.getResponseText());
                }
                if (t != null) {
                    Log.e(TAG, "Notify all devices failed with error: " + t.getLocalizedMessage(), t);
                }
                if (extendedInfo != null) {
                    Log.e(TAG, "Notify all devices failed with: " + extendedInfo.toString());
                }
            }
        });
    }
}



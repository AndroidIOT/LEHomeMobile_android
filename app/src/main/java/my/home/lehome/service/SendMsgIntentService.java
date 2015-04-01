/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package my.home.lehome.service;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import my.home.lehome.R;
import my.home.lehome.helper.DBHelper;
import my.home.lehome.util.Constants;
import my.home.model.entities.ChatItem;

/**
 * Created by legendmohe on 15/3/30.
 */
public class SendMsgIntentService extends IntentService {

    public static final int MSG_END_SENDING = 0;
    public static final int MSG_BEGIN_SENDING = 1;

    public final static String TAG = "SendMsgIntentService";
    public final static String SEND_MSG_INTENT_SERVICE_ACTION = "my.home.lehome.receiver.SendMsgServiceReceiver";

//    private boolean mLocalMsg = false;
//    private ChatItem mCurrentItem;
//    private String mFmtCmd;
//    private String mOriCmd;
//    private String mServerURL;
//    private String mDeviceID;
//    private Messenger mCurMessager;

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     */
    public SendMsgIntentService() {
        super("SendMsgIntentService");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        BeforeSending(intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String cmd = intent.getStringExtra("cmdString");
        String servelURL = intent.getStringExtra("serverUrl");
        String deviceID = intent.getStringExtra("deviceID");
        boolean useLocal = intent.getBooleanExtra("local", false);
        
        String resultString = dispatchSendingTask(servelURL, deviceID, cmd, useLocal);
        AfterSending(intent, resultString);
    }

    private void BeforeSending(Intent intent) {
    	Messenger messenger;
        if (intent.hasExtra("messenger"))
        	messenger = (Messenger) intent.getExtras().get("messenger");
        else
        	messenger = null;
        Message repMsg = Message.obtain();
        repMsg.what = MSG_BEGIN_SENDING;

        String updateString = intent.getStringExtra("update");
//        Log.d(TAG, "recv cmd: \nuseLocal: " + useLocal + "\n"
//                        + "updateString: " + updateString + "\n"
//                        + "mFmtCmd: " + mFmtCmd + "\n"
//                        + "mOriCmd: " + mOriCmd + "\n"
//                        + "mServerURL: " + mServerURL + "\n"
//                        + "mDeviceID: " + mDeviceID
//        );
        
        ChatItem item;
        if (updateString != null) {
        	item = new Gson().fromJson(updateString, ChatItem.class);
        } else {
        	item = new ChatItem();
        	item.setContent(intent.getStringExtra("cmd"));
        	item.setIsMe(true);
        	item.setState(Constants.CHATITEM_STATE_ERROR); // set ERROR
        	item.setDate(new Date());
            DBHelper.addChatItem(getApplicationContext(), item);
        }

        if (messenger != null) {
            Bundle bundle = new Bundle();
            bundle.putBoolean("update", intent.hasExtra("update"));
            bundle.putString("item", new Gson().toJson(item));
            bundle.putLong("update_id", item.getId());
            bundle.putInt("update_state", Constants.CHATITEM_STATE_PENDING);
            repMsg.setData(bundle);
            try {
            	messenger.send(repMsg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        
        intent.putExtra("pass_item", item);
    }

    private String dispatchSendingTask(String servelURL, String deviceID, String cmd, boolean local) {
//        Log.d(TAG, "sending: " + mCurrentItem.getContent() + " use local: " + mLocalMsg);
        if (local) {
            if (TextUtils.isEmpty(servelURL))
                return getErrorJsonString(
                        400,
                        getApplicationContext().getResources().getString(R.string.msg_local_saddress_not_set)
                );
            return sendToLocalServer(servelURL, cmd);
        } else {
            if (TextUtils.isEmpty(deviceID)) {
                return getErrorJsonString(
                        400,
                        getApplicationContext().getResources().getString(R.string.msg_no_deviceid)
                );
            }
            if (TextUtils.isEmpty(servelURL))
                return getErrorJsonString(
                        400,
                        getApplicationContext().getResources().getString(R.string.msg_saddress_not_set)
                );
            return sendToServer(servelURL);
        }
    }

    private String sendToServer(String cmdURL) {
        Context context = getApplicationContext();

        HttpClient httpclient = new DefaultHttpClient();
        HttpResponse response;
        String responseString = null;
        JSONObject repObject = new JSONObject();
        try {
            response = httpclient.execute(new HttpGet(cmdURL));
            StatusLine statusLine = response.getStatusLine();
            if (statusLine.getStatusCode() == HttpStatus.SC_OK) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                response.getEntity().writeTo(out);
                out.close();
                responseString = out.toString();
            } else {
                //Closes the connection.
                response.getEntity().getContent().close();
                try {
                    repObject.put("code", 400);
                    repObject.put("desc", context.getString(R.string.chat_error_conn));
                } catch (JSONException je) {
                }
                responseString = repObject.toString();
            }
        } catch (ClientProtocolException e) {
            try {
                repObject.put("code", 400);
                repObject.put("desc", context.getString(R.string.chat_error_protocol_error));
            } catch (JSONException je) {
            }
            responseString = repObject.toString();
        } catch (IOException e) {
            try {
                repObject.put("code", 400);
                repObject.put("desc", context.getString(R.string.chat_error_http_error));
            } catch (JSONException je) {
            }
            responseString = repObject.toString();
        }
        return responseString;
    }

    private String sendToLocalServer(String serverAddress, String cmd) {
        Context context = getApplicationContext();

        HttpParams httpParameters = new BasicHttpParams();
        int timeoutConnection = 3000;
        HttpConnectionParams.setConnectionTimeout(httpParameters, timeoutConnection);
        int timeoutSocket = 5000;
        HttpConnectionParams.setSoTimeout(httpParameters, timeoutSocket);

        HttpClient httpclient = new DefaultHttpClient(httpParameters);
        HttpResponse response;
        String responseString = null;
        JSONObject repObject = new JSONObject();
        try {
            List<NameValuePair> pairList = new ArrayList<>();
            pairList.add(new BasicNameValuePair("cmd", cmd));
            HttpPost httpPost = new HttpPost(serverAddress);
            httpPost.setEntity(new UrlEncodedFormEntity(pairList, "utf-8"));

            response = httpclient.execute(httpPost);
            StatusLine statusLine = response.getStatusLine();
            if (statusLine.getStatusCode() == HttpStatus.SC_OK) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                response.getEntity().writeTo(out);
                out.close();
                try {
                    repObject.put("code", 200);
                    repObject.put("desc", out.toString());
                } catch (JSONException je) {
                }
                responseString = repObject.toString();
            } else {
                //Closes the connection.
                response.getEntity().getContent().close();
                try {
                    repObject.put("code", 400);
                    repObject.put("desc", context.getString(R.string.chat_error_conn));
                } catch (JSONException je) {
                }
                responseString = repObject.toString();
            }
        } catch (ClientProtocolException e) {
            try {
                repObject.put("code", 400);
                repObject.put("desc", context.getString(R.string.chat_error_protocol_error));
            } catch (JSONException je) {
            }
            responseString = repObject.toString();
        } catch (IOException e) {
            try {
                repObject.put("code", 400);
                repObject.put("desc", context.getString(R.string.chat_error_http_error));
            } catch (JSONException je) {
            }
            responseString = repObject.toString();
        }
        return responseString;
    }

    private void AfterSending(Intent intent, String result) {
        Context context = getApplicationContext();
        int rep_code = -1;
        String desc;
        try {
            JSONObject jsonObject = new JSONObject(result);
            rep_code = jsonObject.getInt("code");
            desc = jsonObject.getString("desc");
        } catch (JSONException e) {
            e.printStackTrace();
            desc = context.getString(R.string.chat_error_json);
        }

        Log.d(TAG, "send cmd finish: " + rep_code + " " + desc);
        Messenger messenger;
        if (intent.hasExtra("messenger"))
        	messenger = (Messenger) intent.getExtras().get("messenger");
        else
        	messenger = null;
        
        Message repMsg = Message.obtain();
        repMsg.what = MSG_END_SENDING;
        long update_id = -1;
        int update_state = -1;

        ChatItem item = intent.getParcelableExtra("pass_item");
        if (rep_code == 200) {
        	item.setState(Constants.CHATITEM_STATE_SUCCESS);
            update_id = item.getId();
            update_state = item.getState();
            DBHelper.updateChatItem(context, item);
        } else {
            if (rep_code == 415) {
            	item.setState(Constants.CHATITEM_STATE_SUCCESS);
            } else {
            	item.setState(Constants.CHATITEM_STATE_ERROR);
            }
            update_id = item.getId();
            update_state = item.getState();
            DBHelper.updateChatItem(context, item);

            ChatItem newItem = new ChatItem();
            newItem.setContent(desc);
            newItem.setIsMe(false);
            newItem.setState(Constants.CHATITEM_STATE_ERROR); // always set true
            newItem.setDate(new Date());
            DBHelper.addChatItem(context, newItem);
            item = newItem;
        }

        if (messenger != null) {
            Bundle bundle = new Bundle();
            bundle.putString("item", new Gson().toJson(item));
            bundle.putInt("rep_code", rep_code);
            bundle.putLong("update_id", update_id);
            bundle.putInt("update_state", update_state);
            repMsg.setData(bundle);
            try {
            	messenger.send(repMsg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private String getErrorJsonString(int code, String error) {
        return "{\"code\":" + code + ",\"desc\":\"" + error + "\"}";
    }
}

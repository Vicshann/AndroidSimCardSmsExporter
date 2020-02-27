
package com.SimSmsExporter;

import android.app.Activity;
import android.widget.Toast;
import android.widget.TextView;
import android.os.Bundle;
import java.util.List;

public class SimSmsExporterActivity extends Activity
{
 String   AccumLog = "";
 TextView tLogView = null;
 SimSmsManager ssmgr;

//-----------------------------------------------------------------------------------------------------------------
private int LogIU(String msg, int toast)
{
 if(toast > 0)Toast.makeText(this, msg, (toast == 1)?Toast.LENGTH_SHORT:Toast.LENGTH_LONG).show(); 
 AccumLog += msg + "\n";
 tLogView.setText(AccumLog);
 return 0;
}
//-----------------------------------------------------------------------------------------------------------------
public void onBackPressed() 
{
 moveTaskToBack(true);
 android.os.Process.killProcess(android.os.Process.myPid());
 System.exit(1);
}
//-----------------------------------------------------------------------------------------------------------------
/** Called when the activity is first created. */
@Override
public void onCreate(Bundle savedInstanceState)
{
 super.onCreate(savedInstanceState);

 tLogView = new TextView(this);
 setContentView(tLogView);

 LogIU("Accessing SIM cards...", 1); 
 ssmgr = new SimSmsManager(SimSmsManager.GetThisProcessName());
 if(ssmgr.IsReady())
  {
   List<String> names = ssmgr.GetRecNames();
   for(String nam : names)
    {
     LogIU("  "+nam, 0);
    }
   LogIU("Reading SMS messages...", 1);
   int mcnt = ssmgr.ReadSmsListForSelectedRecs();   // ReadSmsListForRec(0);
   LogIU("Found " + mcnt + " messages", 1);
   if(mcnt > 0)
    {
     LogIU("Exporting SMS messages...", 1);
     String sms  = ssmgr.ExportSelectedSmsToXml();  
     LogIU("Exported "+ssmgr.GetExportedSmsCount()+" messages", 1);
     String path = ssmgr.SaveSmsFileExternalDocs(sms);
     if(path.length() > 0)LogIU("Saved to "+path, 2);
       else LogIU("Failed to save!", 2); 
    }
  }
   else LogIU("Failed to initialize!", 2); 
}
//-----------------------------------------------------------------------------------------------------------------

}

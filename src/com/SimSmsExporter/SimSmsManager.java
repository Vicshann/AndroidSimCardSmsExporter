
package com.SimSmsExporter;

import android.telephony.SmsMessage;
import android.content.Context;
import android.util.Log;
import android.os.Build;
import android.os.IBinder;
import android.os.Environment;
import java.math.BigInteger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.io.FileOutputStream;
import java.io.File;

public class SimSmsManager
{
 class SuDesc
  {
   public int SimSlotIndex;
   public int SubscriptionId;
  };

 class SimDesc
  {   
   public int SimSlotIndex;
   public int SubscriptionId;   // -1 if not applicable (Old API)
   public String ServiceName;   // In SmsSrvLst
   public String ServiceSesc;   // "Slot 1 : 1"
   public boolean Selected;     // A Marker for batch export
  };

 class SmsDesc implements Comparable<SmsDesc>
  {
   public SmsMessage sms;
   public boolean Selected;

   public int compareTo(SmsDesc obj)
   {
    return Long.compare(this.sms.getTimestampMillis(), obj.sms.getTimestampMillis());     // Makes old messages to be at top of an exported file
   }
  };

 static String MyDbgName = "111111";  //this.getClass().getName() + "Dbg";
 Class SrvMgr;   // android.os.ServiceManager  // Methods are static
 Class ISmsStub;
 Class RawSmsType;
 Object ISms;
 Method meGetService;
 Method meISmsStbAsIntf;
 Method meGetBytesGetBytes;
 Method meCreateFromEfRecord;     // Hidden in SmsMessage
 Method meGetPreferredSmsSubscription;
 Method meGetAllMessagesFromIccEf1;
 Method meGetAllMessagesFromIccEf2;
 Method meGetAllMessagesFromIccEfForSubscriber;
 boolean HaveOfficialMiltiSim = false;
 IBinder IsmsBnd;    // ISMS service interface
 String PackageName;
 String CurrSmsSrvName = "";
 List<SmsDesc> LastSmsList = new ArrayList<SmsDesc>();
 ArrayList<SuDesc> SubsIdLst = new ArrayList<SuDesc>();
 ArrayList<String> SmsSrvLst = new ArrayList<String>();
 ArrayList<String> AllSrvLst = new ArrayList<String>();
 ArrayList<String> ISmsMethodNames = new ArrayList<String>();
 ArrayList<SimDesc> SimSlotList = new ArrayList<SimDesc>();
 int TotalExportedSms = 0;  // Set this to 0 before first call to ExportSelectedSmsToXmlBody

 public boolean IsRdFlg = false;
 public boolean SmsListSorting = true;

//-----------------------------------------------------------------------------------------------------------------
private static int GetClassMethodNames(Class cls, ArrayList<String> Names, boolean Declared)  // A debugger may detach after calling to this function!
{
 Log.i(MyDbgName, "Methods of " + cls.getName());
 Method[] methods = (Declared)?cls.getDeclaredMethods():cls.getMethods(); 
 int total = 0;
 for(Method method : methods)  // Loop through the methods and print out their names
  {
   Log.i(MyDbgName, method.getName());      //  System.out.println(method.getName());
   if(Names != null)Names.add(method.getName());
   total++;
  } 
 return total;
}
//-----------------------------------------------------------------------------------------------------------------
private static int GetStrIndexInList(ArrayList<String> Names, String TargetStr, int MatchIdx, boolean Partial)
{
 int Total = 0;
 int TotalMatch = 0;
 for(String name : Names)
  {
   if((Partial && (name.indexOf(TargetStr) == 0)) || (!Partial && (name.compareTo(TargetStr) == 0)))
    {
     if(TotalMatch == MatchIdx)return Total;
     TotalMatch++;
    }
   Total++;
  }
 return -1;
}
//-----------------------------------------------------------------------------------------------------------------
// Using the same technique as Application.getProcessName() for older devices
// Using reflection since ActivityThread is an internal API
//
// Before API 18, the method was incorrectly named "currentPackageName", but it still returned the process name
// See https://github.com/aosp-mirror/platform_frameworks_base/commit/b57a50bd16ce25db441da5c1b63d48721bb90687
//
public static String GetThisProcessName() 
{
 try 
 {
  Class<?> activityThread = Class.forName("android.app.ActivityThread");
  String methodName = Build.VERSION.SDK_INT >= 18 ? "currentProcessName" : "currentPackageName";
  Method getProcessName = activityThread.getDeclaredMethod(methodName);          // Must be called from a main thread
  return (String) getProcessName.invoke(null); 
 } catch (ClassNotFoundException e) {
  Log.e("ClassNotFoundException","No ActivityThread class found: "+ e.getMessage());
  e.printStackTrace();
  return "";  
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No currentProcessName method found: "+ e.getMessage());
  e.printStackTrace();
  return "";  
 } catch (IllegalAccessException e) {
  Log.e("IllegalAccessException","currentProcessName invocation error: "+ e.getMessage());
  e.printStackTrace();
  return "";
 } catch (IllegalArgumentException e) {
  Log.e("IllegalArgumentException","currentProcessName invocation error: "+ e.getMessage());
  e.printStackTrace();
  return "";
 } catch (InvocationTargetException e) {
  Log.e("InvocationTargetException","currentProcessName invocation error: "+ e.getMessage());
  e.printStackTrace();
  return "";
 }  
}
//-----------------------------------------------------------------------------------------------------------------
private int ListSmsServices(ArrayList<String> SrvList, ArrayList<String> AllList)
{
 SrvList.clear();
 if(SrvMgr == null){Log.e(MyDbgName, "No ServiceManager!"); return 0;}
 int Total = 0;

 try { 
  Method getServicesNames = SrvMgr.getDeclaredMethod("listServices");        // getMethod  
  try {
   String[] strlst = (String[])getServicesNames.invoke(SrvMgr);
   Log.i(MyDbgName, "Registered services: "); 
   for(String name : strlst)
    {
     Log.i(MyDbgName, name);
     if(AllList != null)AllList.add(name);
     if(name.indexOf("isms") == 0)
      {
       if(SrvList != null)SrvList.add(name);
       Total++;
      }
    } 
  } catch (IllegalAccessException e) {
    Log.e("IllegalAccessException","getServicesNames invocation error: "+ e.getMessage());
    e.printStackTrace();
    return 0;
  } catch (IllegalArgumentException e) {
    Log.e("IllegalArgumentException","getServicesNames invocation error: "+ e.getMessage());
    e.printStackTrace();
    return 0;
  } catch (InvocationTargetException e) {
    Log.e("InvocationTargetException","getServicesNames invocation error: "+ e.getMessage());
    e.printStackTrace();
    return 0;
  } 
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No getServicesNames method found: "+ e.getMessage());
  e.printStackTrace();
  return 0;
 }
 return Total;
}
//-----------------------------------------------------------------------------------------------------------------
private int ReadAllSubscriptionIDs(ArrayList<SuDesc> IdLst)     // Starting from Ver 5.1 (API 22)
{
 if(GetStrIndexInList(AllSrvLst, "isub", 0, false) < 0){Log.e(MyDbgName, "No Subscription service found!"); return 0;}

 Class ISubStub = null;
 Class SubInfoType = null;
 Object ISub = null;
 Object ISubBnd = null;
 List<?> SubsList = null;
 Method meGetSimSlotIndex = null;              
 Method meGetSubscriptionId = null;  
 Method meISubStbAsIntf = null;
 Method meGetAllSubInfoList1 = null;   // Old
 Method meGetAllSubInfoList2 = null;   // New

 IdLst.clear();
 int Total = 0;

 try {
  ISubStub = Class.forName("com.android.internal.telephony.ISub$Stub");
 } catch (ClassNotFoundException e) {
  Log.e("ClassNotFoundException","No ISub$Stub found: "+ e.getMessage());
  e.printStackTrace();
  return 0;
 }

 try {
  SubInfoType = Class.forName("android.telephony.SubscriptionInfo");
 } catch (ClassNotFoundException e) {
  Log.e("ClassNotFoundException","No SubscriptionInfo found: "+ e.getMessage());
  e.printStackTrace();
  return 0;
 }

 try { 
   meGetSimSlotIndex   = SubInfoType.getDeclaredMethod("getSimSlotIndex");              
   meGetSubscriptionId = SubInfoType.getDeclaredMethod("getSubscriptionId");  
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No getSimSlotIndex or getSubscriptionId method found: "+ e.getMessage());
  e.printStackTrace();
 }

 try { 
  meISubStbAsIntf = ISubStub.getDeclaredMethod("asInterface", IBinder.class); 
  meISubStbAsIntf.setAccessible(true);      // Already public
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No asInterface method found: "+ e.getMessage());
  e.printStackTrace();
  return 0;
 }

 try {
  ISubBnd = meGetService.invoke(null, "isub");
  if(ISubBnd == null){Log.e(MyDbgName, "Failed to open Subscription service!"); return -2;}
  ISub = meISubStbAsIntf.invoke(null, (IBinder)ISubBnd);
  if(ISub == null){Log.e(MyDbgName, "Failed to get interface for Subscription service!"); return -3;}
//  GetClassMethodNames(ISub.getClass(), null, true);   
 } catch (IllegalAccessException e) {
   Log.e("IllegalAccessException","getService invocation error: "+ e.getMessage());
   e.printStackTrace();
   return 0;
 } catch (IllegalArgumentException e) {
   Log.e("IllegalArgumentException","getService invocation error: "+ e.getMessage());
   e.printStackTrace();
   return 0;
 } catch (InvocationTargetException e) {
   Log.e("InvocationTargetException","getService invocation error: "+ e.getMessage());
   e.printStackTrace();
   return 0;
 }

 try { 
  meGetAllSubInfoList1 = ISub.getClass().getDeclaredMethod("getAllSubInfoList");              
  meGetAllSubInfoList1.setAccessible(true);      // Already public
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No old getAllSubInfoList method found: "+ e.getMessage());
  e.printStackTrace();
 }

 try { 
  meGetAllSubInfoList2 = ISub.getClass().getDeclaredMethod("getAllSubInfoList", java.lang.String.class); 
  meGetAllSubInfoList2.setAccessible(true);      // Already public
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No new getAllSubInfoList method found: "+ e.getMessage());
  e.printStackTrace();
 }
 if((meGetAllSubInfoList1 == null) && (meGetAllSubInfoList2 == null))return 0;

 try {
  if(meGetAllSubInfoList1 != null)SubsList = (List<?>)meGetAllSubInfoList1.invoke(ISub);
    else SubsList = (List<?>)meGetAllSubInfoList2.invoke(ISub, PackageName);   // Expected a correct package name, like "com.SimSmsExporter"
 } catch (IllegalAccessException e) {
   Log.e("IllegalAccessException","GetAllSubInfoList invocation error: "+ e.getMessage());
   e.printStackTrace();
   return 0;
 } catch (IllegalArgumentException e) {
   Log.e("IllegalArgumentException","GetAllSubInfoList invocation error: "+ e.getMessage());
   e.printStackTrace();
   return 0;
 } catch (InvocationTargetException e) {
   Log.e("InvocationTargetException","GetAllSubInfoList invocation error: "+ e.getMessage());
   e.getCause().printStackTrace();
   return 0;
 }
 if(SubsList == null)return 0;
 HaveOfficialMiltiSim = true;
 try {
  for(Object sinf : SubsList)
   {
    SuDesc desc = new SuDesc();
    desc.SimSlotIndex = (Integer)meGetSimSlotIndex.invoke(sinf);   // 0,1,2,3,4,...
    desc.SubscriptionId = (Integer)meGetSubscriptionId.invoke(sinf);  // 1,2,3,4,...
    Log.i("SubscriptionInfo","SimSlotIndex=" + desc.SimSlotIndex + ", SubscriptionId=" + desc.SubscriptionId);
    IdLst.add(desc);
    Total++;
   }
 } catch (IllegalAccessException e) {
   Log.e("IllegalAccessException","GetAllSubInfoList invocation error: "+ e.getMessage());
   e.printStackTrace();
   return 0;
 } catch (IllegalArgumentException e) {
   Log.e("IllegalArgumentException","GetAllSubInfoList invocation error: "+ e.getMessage());
   e.printStackTrace();
   return 0;
 } catch (InvocationTargetException e) {
   Log.e("InvocationTargetException","GetAllSubInfoList invocation error: "+ e.getMessage());
   e.getCause().printStackTrace();
   return 0;
 }
 return Total;
}
//-----------------------------------------------------------------------------------------------------------------
private List<SmsMessage> GetAllSmsFor(SimDesc sdesc)
{
 ArrayList<SmsMessage> SmsList = new ArrayList<SmsMessage>();
 if(sdesc.ServiceName.compareTo(CurrSmsSrvName) != 0)
  {
   if(SelectSmsService(sdesc.ServiceName) < 0){Log.e("GetAllSmsFor","SelectSmsService failed!"); return SmsList;} 
  }
 if(CurrSmsSrvName.length() == 0){Log.e("GetAllSmsFor","SMS Service is not initialized!"); return SmsList;}
 if((meGetAllMessagesFromIccEf1 == null) && (meGetAllMessagesFromIccEf2 == null) && (meGetAllMessagesFromIccEfForSubscriber == null)){Log.e("GetAllSmsFor","GetAllMessagesFromIcc is not initialized!"); return SmsList;}
 List<?> RawSmsList = null;
 try {
//  int suid = (Integer)meGetPreferredSmsSubscription.invoke(ISms);    // <<<< Test
  if(meGetAllMessagesFromIccEfForSubscriber != null)
   {
    RawSmsList = (List<?>)meGetAllMessagesFromIccEfForSubscriber.invoke(ISms, sdesc.SubscriptionId, PackageName); 
   } 
    else if(meGetAllMessagesFromIccEf2 != null)RawSmsList = (List<?>)meGetAllMessagesFromIccEf2.invoke(ISms, PackageName);  
     else if(meGetAllMessagesFromIccEf1 != null)RawSmsList = (List<?>)meGetAllMessagesFromIccEf1.invoke(ISms); 
  if(RawSmsList == null){Log.i("GetAllSmsFor","No SMS records found!"); return SmsList;}
  int count = RawSmsList.size();
  for(int i = 0; i < count; i++) 
   {
    Object rawsms = RawSmsList.get(i);
    if(rawsms == null)continue;     // List contains all records, including "free" records (null)
    byte[] data = (byte[])meGetBytesGetBytes.invoke(rawsms);
    if((data == null)||(data.length == 0))continue;
    SmsMessage sms = (SmsMessage)meCreateFromEfRecord.invoke(null, i+1, data);    
    if(sms != null)SmsList.add(sms); 
/*    
    Log.i(MyDbgName, "TimestampMillis: " + sms.getTimestampMillis());
    Log.i(MyDbgName, "ServiceCenterAddress: " + sms.getServiceCenterAddress());
    Log.i(MyDbgName, "OriginatingAddress: " + sms.getOriginatingAddress());
    Log.i(MyDbgName, "DisplayOriginatingAddress: " + sms.getDisplayOriginatingAddress());
    Log.i(MyDbgName, "MessageBody: " + sms.getMessageBody());
    Log.i(MyDbgName, "DisplayMessageBody: " + sms.getDisplayMessageBody()); */
   }
 } catch (IllegalAccessException e) {
   Log.e("IllegalAccessException","GetAllSubInfoList invocation error: "+ e.getMessage());
   e.printStackTrace();
   return SmsList;
 } catch (IllegalArgumentException e) {
   Log.e("IllegalArgumentException","GetAllSubInfoList invocation error: "+ e.getMessage());
   e.printStackTrace();
   return SmsList;
 } catch (InvocationTargetException e) {
   Log.e("InvocationTargetException","GetAllSubInfoList invocation error: "+ e.getMessage());      // TODO: Need more deep exception logging because printStackTrace logs nothing useful (See depths of InvocationTargetException for detailed info)
   e.printStackTrace();  // e.getCause().printStackTrace();
   return SmsList;
 }

 return SmsList;
}
//-----------------------------------------------------------------------------------------------------------------
private int SelectSmsService(int Index)
{
 if(Index >= SmsSrvLst.size()){Log.e(MyDbgName, "No SMS services in the list!"); return -1;}
 return SelectSmsService(SmsSrvLst.get(Index));
}
//-----------------------------------------------------------------------------------------------------------------
private int SelectSmsService(String Name)    // Usually just one - "isms"
{
 CurrSmsSrvName = "";
 meGetAllMessagesFromIccEf1 = null;
 meGetAllMessagesFromIccEf2 = null;
 meGetAllMessagesFromIccEfForSubscriber = null;

 try {
  Object obj = meGetService.invoke(null, Name);
  if(obj == null){Log.e(MyDbgName, "Failed to open SMS service: " + Name); return -2;}
  IsmsBnd = (IBinder)obj;
  ISms = meISmsStbAsIntf.invoke(null, IsmsBnd);
  if(ISms == null){Log.e(MyDbgName, "Failed to get interface for SMS service: " + Name); return -3;}

// >>> Initialize right methods here?      // If only one isms service then try a different subscriber id
  ISmsMethodNames.clear();
  GetClassMethodNames(ISms.getClass(), ISmsMethodNames, true);   

  try {    // <<<<<<<<<<<<<< For testing
   meGetPreferredSmsSubscription = ISms.getClass().getDeclaredMethod("getPreferredSmsSubscription");     // int getPreferredSmsSubscription()    
   meGetPreferredSmsSubscription.setAccessible(true);      // Already public
  } catch (NoSuchMethodException e) {
   Log.e("NoSuchMethodException","No getPreferredSmsSubscription method found: "+ e.getMessage());
   e.printStackTrace();
  }

  try { 
   meGetAllMessagesFromIccEf1 = ISms.getClass().getDeclaredMethod("getAllMessagesFromIccEf");     // List<SmsRawData> getAllMessagesFromIccEf()         
   meGetAllMessagesFromIccEf1.setAccessible(true);      // Already public
  } catch (NoSuchMethodException e) {
   Log.e("NoSuchMethodException","No old getAllMessagesFromIccEf method found: "+ e.getMessage());
   e.printStackTrace();
  }

  try {                                                                                        
   meGetAllMessagesFromIccEf2 = ISms.getClass().getDeclaredMethod("getAllMessagesFromIccEf", String.class);    // List<SmsRawData> getAllMessagesFromIccEf(String callingPkg)          
   meGetAllMessagesFromIccEf2.setAccessible(true);      // Already public
  } catch (NoSuchMethodException e) {
   Log.e("NoSuchMethodException","No new getAllMessagesFromIccEf method found: "+ e.getMessage());
   e.printStackTrace();
  }

  try {                                                                                        
   meGetAllMessagesFromIccEfForSubscriber = ISms.getClass().getDeclaredMethod("getAllMessagesFromIccEfForSubscriber", int.class, String.class);    // List<SmsRawData> getAllMessagesFromIccEfForSubscriber(in int subId, String callingPkg);          
   meGetAllMessagesFromIccEfForSubscriber.setAccessible(true);      // Already public
  } catch (NoSuchMethodException e) {
   Log.e("NoSuchMethodException","No getAllMessagesFromIccEfForSubscriber method found: "+ e.getMessage());
   e.printStackTrace();
  }

  if((meGetAllMessagesFromIccEf1 == null) && (meGetAllMessagesFromIccEf2 == null) && (meGetAllMessagesFromIccEfForSubscriber == null)){Log.i("SelectSmsService","Unknown SMS service: "+ Name); return -9;}
  CurrSmsSrvName = Name;
 } catch (IllegalAccessException e) {
   Log.e("IllegalAccessException","getService invocation error: "+ e.getMessage());
   e.printStackTrace();
   return -4;
 } catch (IllegalArgumentException e) {
   Log.e("IllegalArgumentException","getService invocation error: "+ e.getMessage());
   e.printStackTrace();
   return -5;
 } catch (InvocationTargetException e) {
   Log.e("InvocationTargetException","getService invocation error: "+ e.getMessage());
   e.printStackTrace();
   return -6;
 }
 return 0;
}
//-----------------------------------------------------------------------------------------------------------------
private int RebuildSimSlotList()
{
 int Total = 0;
 SimSlotList.clear();
 int SlotBase = 1;
 if(HaveOfficialMiltiSim && (SelectSmsService("isms") >= 0))
  {
   for(SuDesc subinf : SubsIdLst)
    {
     if(subinf.SimSlotIndex < 0)continue;  // No SIM card in this slot
     SimDesc sd = new SimDesc();
     sd.Selected       = true;
     sd.SimSlotIndex   = subinf.SimSlotIndex+1;
     sd.SubscriptionId = subinf.SubscriptionId;     // Pass as 1 if required by getAllMessagesFromIccEf
     sd.ServiceName    = "isms";
     sd.ServiceSesc    = "Slot " + sd.SimSlotIndex + " : " + sd.SubscriptionId;   // "Slot 1 : 1"  (New)
     SimSlotList.add(sd);
     if(sd.SimSlotIndex > SlotBase)SlotBase = sd.SimSlotIndex;
     Log.i("RebuildSimSlotList",sd.ServiceSesc + " for new " + sd.ServiceName);
    }
  }
 for(String srvname : SmsSrvLst)   // For each SMS service (After Ver 5.1 should be 1 because MultiSIM support has been added)
  {
   if(HaveOfficialMiltiSim && (srvname.compareTo("isms") == 0))continue;  // Already processed   
   if(SelectSmsService(srvname) < 0)continue;   // Not available
   SimDesc sd = new SimDesc();
   sd.Selected       = true;
   sd.SimSlotIndex   = SlotBase;
   sd.SubscriptionId = -1;     // Pass as 1 if required by getAllMessagesFromIccEf
   sd.ServiceName    = srvname;
   sd.ServiceSesc    = "Slot " + sd.SimSlotIndex;   // "Slot 1"  (Old)
   SimSlotList.add(sd);
   SlotBase++;
   Log.i("RebuildSimSlotList",sd.ServiceSesc + " for old " + sd.ServiceName);
  }
 return Total;
}
//-----------------------------------------------------------------------------------------------------------------
private String MakeXmlFileEnd() {return "</allsms>";}
private String MakeXmlFileCount(int Cnt) {return "<allsms count=\"" + Cnt + "\">\n";}
private String MakeXmlFileBegin() {return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";}
private String MakeNamedXmlParam(String Name, String Value) {return Name + "=\"" + Value + "\" ";}
//-----------------------------------------------------------------------------------------------------------------
private String ExportSelectedSmsToXmlBody()   // XML format for "Super Backup"
{
 String result = "";        
 if(LastSmsList == null)return "";
 SimpleDateFormat sdtfmt = new SimpleDateFormat("dd MMM yyyy HH:mm:ss");
 for(SmsDesc desc : LastSmsList)
  {
   if(!desc.Selected)continue;
   String line = "\t<sms ";
   String time = "";
   time += desc.sms.getTimestampMillis();
   line += MakeNamedXmlParam("address", desc.sms.getOriginatingAddress());
   line += MakeNamedXmlParam("time", sdtfmt.format(new Date(desc.sms.getTimestampMillis())));      // Parsed TimestampMillis   // "20 feb. 2020 y. 15:33:46"  // E, dd MMM yyyy HH:mm:ss
   line += MakeNamedXmlParam("date", time);
   line += MakeNamedXmlParam("type", (desc.sms.getServiceCenterAddress().length() > 0)?"1":"2");     // 2 if service_center string is empty
   line += MakeNamedXmlParam("body", desc.sms.getMessageBody());
   line += MakeNamedXmlParam("read", "1");    // Always mark as read
   line += MakeNamedXmlParam("service_center", desc.sms.getServiceCenterAddress());
   line += MakeNamedXmlParam("name", "");    // Always empty
   line += "/>\n"; 
   result += line;
   TotalExportedSms++;
  }
 return result;
}
//-----------------------------------------------------------------------------------------------------------------
public SimSmsManager(String pkname) 
{
 PackageName = pkname;
 Log.i(MyDbgName, "Package name: "+PackageName);

 try {
  SrvMgr = Class.forName("android.os.ServiceManager");
 } catch (ClassNotFoundException ex) {
  Log.e("ClassNotFoundException","No ServiceManager class found: "+ ex.getMessage());
  ex.printStackTrace();
  return;
 }

 try {
  ISmsStub = Class.forName("com.android.internal.telephony.ISms$Stub");
 } catch (ClassNotFoundException ex) {
  Log.e("ClassNotFoundException","No ISms$Stub class found: "+ ex.getMessage());
  ex.printStackTrace();
  return;
 }

  try {
  RawSmsType = Class.forName("com.android.internal.telephony.SmsRawData");
 } catch (ClassNotFoundException ex) {
  Log.e("ClassNotFoundException","No SmsRawData class found: "+ ex.getMessage());
  ex.printStackTrace();
  return;
 }

 try { 
  meCreateFromEfRecord = SmsMessage.class.getDeclaredMethod("createFromEfRecord", int.class, byte[].class);      // public static SmsMessage createFromEfRecord(int index, byte[] data)
  meCreateFromEfRecord.setAccessible(true);      
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No createFromEfRecord method found: "+ e.getMessage());
  e.printStackTrace();
  return;
 }

 try { 
  meGetBytesGetBytes = RawSmsType.getDeclaredMethod("getBytes");      // public byte[] getBytes()
  meGetBytesGetBytes.setAccessible(true);      
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No getBytes method found: "+ e.getMessage());
  e.printStackTrace();
  return;
 }

 try { 
  meGetService = SrvMgr.getDeclaredMethod("getService", String.class); 
  meGetService.setAccessible(true);      // Already public
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No getService method found: "+ e.getMessage());
  e.printStackTrace();
  return;
 }

 try { 
  meISmsStbAsIntf = ISmsStub.getDeclaredMethod("asInterface", IBinder.class); 
  meISmsStbAsIntf.setAccessible(true);      // Already public
 } catch (NoSuchMethodException e) {
  Log.e("NoSuchMethodException","No asInterface method found: "+ e.getMessage());
  e.printStackTrace();
  return;
 }

// GetClassMethodNames(SrvMgr, true);   // <<<<<<<<<<<<<<<< DEBUG
 if(ListSmsServices(SmsSrvLst, AllSrvLst) <= 0){Log.e(MyDbgName, "No SMS services found!"); return;}
 ReadAllSubscriptionIDs(SubsIdLst); 
 if(SubsIdLst.size() == 0)Log.i(MyDbgName, "NOTE: No sim cards present or the system version below 5.1(API 22)");
 RebuildSimSlotList();

 IsRdFlg = true;
} 
//-----------------------------------------------------------------------------------------------------------------
public boolean IsReady(){return IsRdFlg;}
//-----------------------------------------------------------------------------------------------------------------
public boolean RefreshSlotList(){return RebuildSimSlotList() > 0;}
//-----------------------------------------------------------------------------------------------------------------
public int GetReadySmsCount() {return LastSmsList.size();}   
//-----------------------------------------------------------------------------------------------------------------
public int GetExportedSmsCount() {return TotalExportedSms;} 
//-----------------------------------------------------------------------------------------------------------------
public List<String> GetRecNames()
{
 ArrayList<String> lst = new ArrayList<String>();
 for(SimDesc sdesc : SimSlotList)
  {
   lst.add(sdesc.ServiceSesc);
  }
 return lst;
}
//-----------------------------------------------------------------------------------------------------------------
public int ReadSmsListForRec(int RecIndex)
{
 LastSmsList.clear();    // Make it optional?
 int TotalAdded = 0;
 if(RecIndex >= SimSlotList.size()){Log.e(MyDbgName, "RecIndex is too big!"); return 0;}
 SimDesc sdesc = SimSlotList.get(RecIndex);
 List<SmsMessage> SmsList = GetAllSmsFor(sdesc);
 if(SmsList == null)return 0;
 for(SmsMessage sms : SmsList)
  {
   SmsDesc sr = new SmsDesc();
   sr.sms = sms;
   sr.Selected = true;
   LastSmsList.add(sr);
   TotalAdded++;
  }  
 if(SmsListSorting)Collections.sort(LastSmsList);
 return TotalAdded;
}
//-----------------------------------------------------------------------------------------------------------------
public int ReadSmsListForSelectedRecs()
{
 LastSmsList.clear();      // Make it optional?
 int TotalAdded = 0;
 for(SimDesc sdesc : SimSlotList)
  {
   if(!sdesc.Selected)continue;
   List<SmsMessage> SmsList = GetAllSmsFor(sdesc);
   if(SmsList == null)continue;
   for(SmsMessage sms : SmsList)
    {
     SmsDesc sr = new SmsDesc();
     sr.sms = sms;
     sr.Selected = true;
     LastSmsList.add(sr);
     TotalAdded++;
    }              
  }
 if(SmsListSorting)Collections.sort(LastSmsList);
 return TotalAdded;
}
//-----------------------------------------------------------------------------------------------------------------
public String ExportSelectedSmsToXml()
{
 if(LastSmsList.size() == 0)return "";
 TotalExportedSms = 0;
 String result = MakeXmlFileBegin();
 String body = ExportSelectedSmsToXmlBody();
 result += MakeXmlFileCount(TotalExportedSms);
 result += body;
 result += MakeXmlFileEnd();
 return result;
}
//-----------------------------------------------------------------------------------------------------------------
public static String SaveSmsFileExternalDocs(String Data)
{
// String state = Environment.getExternalStorageState();
// if(!Environment.MEDIA_MOUNTED.equals(state)){Log.e(MyDbgName, "No external storage available!"); return "";} 
 SimpleDateFormat sdtfmt = new SimpleDateFormat("yyyymmddHHmmss");

 File docsFolder = new File(Environment.getExternalStorageDirectory() + "/Documents"); // Environment.getExternalStorageDirectory() gives a File back   // DIRECTORY_DOCUMENTS is only available in Android 4.4
 boolean isPresent = true;
 if(!docsFolder.exists())isPresent = docsFolder.mkdir();
 if(!isPresent){Log.e(MyDbgName, "Failed to access Documents directory!"); return "";} 

 String FileName = "sms_" + sdtfmt.format(new Date())+".xml";
 String FilePathName = docsFolder.getAbsolutePath() + "/" + FileName;
 File file =  new File(docsFolder.getAbsolutePath(),FileName); 
 try {
  file.createNewFile();          
  FileOutputStream outputStream = new FileOutputStream(file, true);   //second argument of FileOutputStream constructor indicates whether to append or create new file if one exists
  outputStream.write(Data.getBytes(Charset.forName("UTF-8")));
  outputStream.flush();
  outputStream.close();
 } catch (Exception e) {
  Log.e(MyDbgName, "Failed to save: " + FilePathName);
  e.printStackTrace();
 }
 return FilePathName;
}
//-----------------------------------------------------------------------------------------------------------------
}

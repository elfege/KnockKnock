/*
*  Copyright 2016 elfege
*
*    Software distributed under the License is distributed on an "AS IS" BASIS, 
*    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*    for the specific language governing permissions and limitations under the License.
*
*    Light / motion Management
*
*  Author: Elfege
*/

import java.text.SimpleDateFormat
import groovy.transform.Field
import groovy.json.JsonOutput


@Field static int delays = 0


definition(
    name: "Knock Knock",
    namespace: "elfege",
    author: "elfege",
    description: "Tell you when someone is knocking on the door",
    category: "Convenience",
    iconUrl: "http://static1.squarespace.com/static/5751f711d51cd45f35ec6b77/t/59c561cb268b9638e8ba6c23/1512332763339/?format=1500w",
    iconX2Url: "http://static1.squarespace.com/static/5751f711d51cd45f35ec6b77/t/59c561cb268b9638e8ba6c23/1512332763339/?format=1500w",
    iconX3Url: "http://static1.squarespace.com/static/5751f711d51cd45f35ec6b77/t/59c561cb268b9638e8ba6c23/1512332763339/?format=1500w",
)

preferences {

    page name:"pageSetup"

}
def pageSetup() {

    boolean haveDim = false


    def pageProperties = [
        name:       "pageSetup",
        title:      "${app.label}",
        nextPage:   null,
        install:    true,
        uninstall:  true
    ]

    return dynamicPage(pageProperties) {
        if(atomicState.paused == true)
        {
            atomicState.button_name = "resume"
            logging("button name is: $atomicState.button_name")
        }
        else 
        {
            atomicState.button_name = "pause"
            logging("button name is: $atomicState.button_name")
        }
        section("")
        {
            input "pause", "button", title: "$atomicState.button_name"
        }
        /*********************/
        section("sensors")
        {
            input "accel", "capability.accelerationSensor", title: "Select acceleration sensors", multiple:true, required:true, submitOnChange: true 
            input "rename", "bool", title: "Give a nick name to each one of my sensors for better audio notification", defaultValue:false, submitOnChange:true
            if(rename)
            {
                int i = 0
                int s = accel.size()
                for(s!=0;i<s;i++)
                {
                    input "nickName${i}", "text", title: "Rename ${accel[i]}", required:true
                }
            }
            input "ignoreLock", "bool", title: "Ignore vibrations coming from a lock", submitOnChange:true, defaultValue:false
            if(ignoreLock)
            {
                input "lockToIgnore", "capability.lock", title: "Select your lock", multiple:true, required:true 
            }
        }
        section("Notification devices")
        {
            input "speaker", "capability.audioNotification", title: "Select your speakers", multiple:true, required:true, submitOnChange: true 
            if(speaker)
            {
                input "volumeLevel", "number", title: "Set the volume level", range: "0..100",required:true, submitOnChange: true 
                input "textToSpeak", "text", title: "Text to play", required:true, submitOnChange: true 
            }
            input "notification", "capability.notification", title: "Select notification devices", multiple:true, required:true, submitOnChange: true 
        }

        section("modes")        
        {
            input "restrictedModes", "mode", title:"Pause this app if location is in one of these modes", required: false, multiple: true
        }

        section() {
            label title: "Assign a name", required: false
        }
        section("logging")
        {
            input "enablelogging", "bool", title:"Enable logging", value:false, submitOnChange:true
            input "enabledescriptiontext", "bool", title:"Enable description text", value:false, submitOnChange:true
        }
        section()
        {
            if(atomicState.installed)
            {
                input "update", "button", title: "UPDATE"
                input "run", "button", title: "RUN"
            }
        }
    }
}

def installed() {
    logging("Installed with settings: ${settings}")
    atomicState.installed = true
    initialize()

}
def updated() {
    descriptiontext "updated with settings: ${settings}"
    atomicState.installed = true        
    unsubscribe()
    unschedule()
    initialize()
}
def initialize() {

    atomicState.lastContactEvent = atomicState.lastContactEvent != null ? atomicState.lastContactEvent : now()

    if(enablelogging == true){
        atomicState.EnableDebugTime = now()
        runIn(1800, disablelogging)
        descriptiontext "disablelogging scheduled to run in ${1800/60} minutes"
    }
    else 
    {
        log.warn "debug logging disabled!"
    }

    subscribe(accel, "acceleration", mainHandler)

    if(lockToIgnore)
    {
        subscribe(lockToIgnore, "lock", mainHandler)
    }

    boolean hasContactCap = accel.every{element -> element.hasCapability("ContactSensor")}
    if(hasContactCap)
    {
        log.trace "$accel all have contact capability. This will be usefull to prevent false positives"
        subscribe(accel, "contact", mainHandler)
    }
    else
    {
        log.trace "$accel don't have contact capability"
    }
}

def appButtonHandler(btn) {

    switch(btn) {
        case "pause":atomicState.paused = !atomicState.paused
        log.debug "atomicState.paused = $atomicState.paused"
        if(atomicState.paused)
        {
            log.debug "unsuscribing from events..."
            unsubscribe()  
            log.debug "unschedule()..."
            unschedule()
            break
        }
        else
        {
            updated()            
            break
        }
        case "update":
        atomicState.paused = false
        updated()
        break
        case "run":
        if(!atomicState.paused)  
        {
            master()
        }
        else
        {
            log.warn "App is paused!"
        }
        break

    }
}
def locationModeChangeHandler(evt){
    logging("$evt.name is now in $evt.value mode")   
}
def mainHandler(evt){

    if(atomicState.paused == true)
    {
        return
    }
    
    log.warn "${evt.name}: $evt.device is $evt.value"

    if(location.mode in restrictedModes)
    {
        descriptiontext "location in restricted mode, doing nothing"
        return
    }


    if(evt.value in ["open", "closed","locked", "unlocked"])
    {
        unschedule(process) // cancel previously scheduled voice notifications

        log.info "other device event, false alarm" 
        atomicState.lastContactEvent = now()
        return
    }

    boolean evtTooClose = now() - atomicState.lastContactEvent < 10000
    log.debug "evtTooClose = $evtTooClose"

    if(evt.value == "active") 
    {
        if(evtTooClose)
        {
            log.warn "Active event too close to a contact event, false alarm."
            unschedule(process) // cancel previously scheduled voice notifications
            return
        }

        runIn(2, process, [data:evt.device.toString()])


    }
}

def process(String device)
{

    log.info """--- device is: $device
accel = $accel
"""

    def devName = device
    def devObject = settings.find{it.key == "$device"}.value

    def list = ["a", "b", "c"]
    log.warn """
accel = ${accel}
${accel.indexOf(accel[0])}
devObject = $devObject
"""
    return

    if(rename)
    {

        def index = accel.toString().indexOf(device)
        logging "*************************************index number is $index, looking for entry ''nickName${index}''"
        devName = settings.find{it.key == "nickName$index"}.value
        logging "devName found = $devName"
        log.trace "settings = $settings"

    }

    def message = "$textToSpeak $devName"

    log.trace "playing ''${message}'' with volume level: $volumeLevel" 

    if( speaker.every{element -> element.hasCapability("MusicPlayer")})
    {
        speaker.setLevel(volumeLevel.toInteger())
        pauseExecution(500)
        speaker.playText(message) 
    }
    else 
    {
        speaker.playTextAndRestore(message, volumeLevel.toInteger())
        notification?.deviceNotification(message)
    }
}

// for test runs 
def master(){
    def devName = accel[0]
    log.info "**** devName is: $devName"

    runIn(2, process, [data:devName.toString()])
}

def logging(msg){
    //log.warn "enablelogging ? $enablelogging" 
    if (enablelogging) log.debug msg
    if(debug && atomicState.EnableDebugTime == null) atomicState.EnableDebugTime = now()
}
def descriptiontext(msg){
    //log.warn "enabledescriptiontext = ${enabledescriptiontext}" 
    if (enabledescriptiontext) log.info msg
}
def disablelogging(){
    app.updateSetting("enablelogging",[value:"false",type:"bool"])
    log.warn "logging disabled!"
}



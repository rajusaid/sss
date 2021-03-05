import boto3, json

ec2 = boto3.client("ec2", region_name="eu-west-2")

def lambda_handler(event, context):
    #Read data from JSON file
    try:
        autoStartData = readAutoStartData()
    except Exception as e:
        print(f"ERROR: Failed to read autostart data - {e}")
        return{
            "statusCode": 500,
            "body": f"Failed to read autostart data - {e}"
        }
    #Loop through platforms
    print("INFO: Starting platforms...")
    for platformName, platformData in autoStartData["platforms"].items():
        print(f"INFO: Starting {platformName} instances...")
        for envName, startInstances in platformData["environments"].items():
            if startInstances == True:
                try:
                    print(f"INFO: Starting {envName} {platformName} instances...")
                    instances = getEc2Details(platformName, envName)
                    for instance in instances:
                        if instance["State"]["Name"] == "stopped":
                            ec2.start_instances(InstanceIds=[instance['InstanceId']])
                        else:
                            print(f"WARNING: Could not start instance {instance['InstanceId']} because it was in state \"{instance['State']['Name']}\"")
                except Exception as e:
                    print(f"WARNING: Could not start {envName} {platformName} instances - {e}")
            else:
                print(f"INFO: Skipping {envName} {platformName} instances...")
    #Handle individual instances
    print("INFO: Starting additional instances...")
    for instanceName in autoStartData["additionalInstances"]:
        instance = getSingleInstanceData(instanceName)
        if instance["State"]["Name"] == "stopped":
            print(f"INFO: Starting {instanceName} ({instance['InstanceId']})...")
            ec2.start_instances(InstanceIds=[instance['InstanceId']])
        else:
            print(f"WARNING: Could not start instance {instance['InstanceId']} because it was in state \"{instance['State']['Name']}\"")

    return{
        "statusCode": 200,
        "body": "Function executed successfully."
    }

def getEc2Details(platform, environment):
    rawInstances = ec2.describe_instances(
        Filters = [
            {
                "Name": "tag:platform",
                "Values": [platform]
            },
            {
                "Name": "tag:environment",
                "Values": [environment]
            }
        ]
    )
    #Formatting into single array (as the data comes in separate reservations)
    returnArray = []
    for reservation in rawInstances["Reservations"]:
        for Instance in reservation["Instances"]:
            returnArray.append(Instance)
    return returnArray

def getSingleInstanceData(instanceName):
    instanceData = ec2.describe_instances(Filters = [{"Name": "tag:Name", "Values": [instanceName]}])
    return instanceData["Reservations"][0]["Instances"][0]

def readAutoStartData():
    f = open("autoStartData.json", "r")
    data = json.loads(f.read())
    f.close()
    return data
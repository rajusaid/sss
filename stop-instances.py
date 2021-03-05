import boto3, json

ec2 = boto3.client("ec2", region_name="eu-west-2")

def lambda_handler(event, context):
    #Read data from JSON file
    try:
        autoStopData = readautoStopData()
    except Exception as e:
        print(f"ERROR: Failed to read autostop data - {e}")
        return{
            "statusCode": 500,
            "body": f"Failed to read autostop data - {e}"
        }
    #Loop through platforms
    print("INFO: Stopping platforms...")
    for platformName, platformData in autoStopData["platforms"].items():
        print(f"INFO: Stopping {platformName} instances...")
        for envName, stopInstances in platformData["environments"].items():
            if stopInstances == True:
                try:
                    print(f"INFO: Stopping {envName} {platformName} instances...")
                    instances = getEc2Details(platformName, envName)
                    for instance in instances:
                        if instance["State"]["Name"] == "running":
                            ec2.stop_instances(InstanceIds=[instance['InstanceId']])
                        else:
                            print(f"WARNING: Could not stop instance {instance['InstanceId']} because it was in state \"{instance['State']['Name']}\"")
                except Exception as e:
                    print(f"WARNING: Could not stop {envName} {platformName} instances - {e}")
            else:
                print(f"INFO: Skipping {envName} {platformName} instances...")
    #Handle individual instances
    print("INFO: Stopping additional instances...")
    for instanceName in autoStopData["additionalInstances"]:
        instance = getSingleInstanceData(instanceName)
        if instance["State"]["Name"] == "running":
            print(f"INFO: Stopping {instanceName} ({instance['InstanceId']})...")
            ec2.stop_instances(InstanceIds=[instance['InstanceId']])
        else:
            print(f"WARNING: Could not stop instance {instance['InstanceId']} because it was in state \"{instance['State']['Name']}\"")

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

def readautoStopData():
    f = open("autoStopData.json", "r")
    data = json.loads(f.read())
    f.close()
    return data
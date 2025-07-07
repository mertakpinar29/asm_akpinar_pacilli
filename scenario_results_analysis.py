import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns

df = pd.read_csv("raft_simulation_results.csv")

sns.set(style="whitegrid")

df["scenario"] = pd.Categorical(df["scenario"], categories=[
    "Normal Startup", "Split Vote", "Frequent Crashes"
], ordered=True)

labels = ["Odd number of nodes", "Even number of nodes", "Frequent Crashes"]

def plot_box(metric, ylabel):
    plt.figure(figsize=(8, 5))
    ax = sns.boxplot(x="scenario", y=metric, data=df, palette="Set2")
    ax.set_xticklabels(labels, rotation=15)
    plt.ylabel(ylabel)
    plt.title(f"{ylabel} across Raft Simulation Scenarios")
    plt.tight_layout()
    plt.show()


plot_box("firstLeaderTime", "Time to First Leader (s)")
plot_box("splitVoteCount", "Split Vote Events")
plot_box("avgLeaderTenure", "Average Leader Tenure (s)")
plot_box("crashEvents", "Leader Crashes")
plot_box("reElections", "Leader Re-Elections")

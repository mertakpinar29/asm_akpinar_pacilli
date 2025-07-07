# CTMC-Based Analysis of Raft Leader Election

This project explores the **leader election mechanism of the Raft consensus algorithm** through the lens of **stochastic modeling**, **formal verification**, and **aggregate computing**. Implemented as part of the *Advanced Software Modelling and Design* course, the project combines:

- A **Scala-based Continuous-Time Markov Chain (CTMC)** simulator
- **Formal verification in PRISM**
- **A ScaFi-based aggregate simulation** for spatial and decentralized behavior

---

## 📌 Project Goals

- Model Raft’s **leader election** protocol using **CTMCs**
- Analyze **timing dynamics**, **vote contention**, and **crash recovery**
- Verify correctness properties using **PRISM**
- Demonstrate **decentralized coordination** via **ScaFi**

---

## 📁 Components

### 1. Scala CTMC Model

Implements a stochastic CTMC version of Raft’s leader election mechanism:

- **Node states**: `Follower`, `Candidate`, `Leader`, `Crashed`
- **Transitions**: timeouts, vote requests, heartbeats, crashes, recoveries
- **Exponential transition rates** model timeouts and failures
- Simulations include:
    - 🟢 Normal startup (odd #nodes)
    - ⚠️ Split votes (even #nodes)
    - 🔁 Frequent crashes

### 2. PRISM Formal Verification

CTMC models of 3-node and 4-node clusters are verified for:

- ✅ **Leader uniqueness**
- 🔁 **Term consistency**
- ⏱️ **Election convergence within time bounds**
- 🧾 **Term divergence bounds**

### 3. ScaFi Aggregate Simulation

Raft election is modeled using ScaFi’s `rep`, `nbr`, and `foldhood` constructs:

- Local interactions lead to **global coordination**
- Simulates:
    - Node isolation and reintegration
    - Manual leader demotion
- Confirms **self-stabilizing** behavior and term synchronization

---

## 📊 Key Results

- Odd-sized clusters elect leaders faster and more reliably
- Even-sized clusters are more prone to **split votes**
- Raft recovers effectively from **frequent crashes**
- PRISM confirms key properties of **safety** and **liveness**
- ScaFi simulations illustrate **decentralized recovery** and **self-stabilization**

---

## 👥 Authors

- **Mert Akpinar** – mert.akpinar@studio.unibo.it
- **Benedetta Pacilli** – benedetta.pacilli@studio.unibo.it

---

## 📄 License
This project is licensed under the GNU General Public License v3.0
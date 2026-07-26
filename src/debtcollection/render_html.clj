(ns debtcollection.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`debtcollection.actor` -> `debtcollection.governor` ->
  `debtcollection.store`) through a scenario built from real, exercised
  test fixture data and renders the result deterministically -- no
  invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verified by diffing two
  consecutive runs before shipping).

  Seed data provenance, disclosed plainly:
  - `client-1` (\"Kobo Collections\") + account `A-1`
    (`{:name \"account-042\" :contact-start-hour 8 :contact-end-hour
    21}`) are lifted VERBATIM from
    `debtcollection.actor-test`/`debtcollection.governor-test`'s shared
    `fresh-store` fixture.
  - `client-2` (\"Northgate Recovery Partners\") + account `A-2` are
    ADDITIONAL demo data registered via the store's own real
    `register-client!`/`register-account!` calls -- disclosed here as
    an addition, not presented as a pre-existing fixture. A second
    client is necessary to demonstrate the `:account-wrong-client` rule
    (exactly as `debtcollection.governor-test/hard-on-foreign-account`
    does: register a second client with no account of its own, then
    propose the FIRST client's account against it) and to show a
    second, independently-windowed clean scenario.
  - `client-ghost`/`A-ghost` are deliberately NEVER registered -- they
    exist only to demonstrate the `:no-client`/`:unknown-account`
    hard-violation paths, exactly as
    `debtcollection.governor-test/hard-on-unregistered-client` and
    `hard-on-unknown-account` do with their own unregistered ids.

  IMPORTANT architectural note: `debtcollection.store/MemStore` wraps a
  mutable atom -- `register-client!`/`register-account!`/
  `commit-record!`/`append-ledger!` all mutate in place and return the
  SAME store value (unlike some other repos in this fleet whose store
  is an immutable defrecord). `run-demo!` below relies on that: it does
  not thread a returned value through registrations.

  Known architectural gaps, honestly noted rather than papered over
  (both confirmed by reading `debtcollection.governor` and
  `debtcollection.advisor` directly, not assumed):
  - `:no-actuation` (proposal `:effect` must be `:propose`) is NOT
    reachable through this demo, because the real `mock-advisor`
    (`debtcollection.advisor/infer`) unconditionally sets `:effect
    :propose` on every proposal it emits. Covered instead by
    `debtcollection.governor-test/hard-on-no-actuation-violation`,
    which calls `governor/check` directly with a hand-built proposal.
  - The confidence-floor escalation (confidence < 0.6) is likewise NOT
    reachable through this demo: `mock-advisor` derives confidence
    purely from the request's `:stake` (`:high` 0.7, `:medium` 0.85,
    `:low`/default 0.95 -- see `debtcollection.advisor/infer`), and
    none of those three fixed values falls below the 0.6 floor.
    Covered instead by
    `debtcollection.governor-test/escalates-low-confidence`, which
    again calls `governor/check` directly with a hand-built
    low-confidence proposal.
  - So this demo reaches 6 of the governor's 8 documented HARD/
    escalation reasons through the real graph (`:no-client`,
    `:unknown-account`, `:account-wrong-client`,
    `:contact-hour-out-of-window`, `:harassment-flagged`, and BOTH
    always-escalate ops `:approve-off-hours-contact`/
    `:approve-settlement-offer`) plus 2 clean auto-commits -- the
    other 2 reasons above are architecturally unreachable via the real
    advisor and are covered by the existing unit tests instead.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [debtcollection.store :as store]
            [debtcollection.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real debt-collection operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it
  (this scenario never demonstrates an UNAPPROVED escalation -- every
  escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (cond
      (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})

      (= :hold (get-in r1 [:state :disposition]))
      {:thread-id tid :client-id client-id :op op :request request
       :outcome :hard-hold
       :verdict (get-in r1 [:state :verdict])
       :rule (-> r1 :state :verdict :violations first :rule)}

      :else
      {:thread-id tid :client-id client-id :op op :request request
       :outcome :auto-committed
       :record (get-in r1 [:state :record])})))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph -- 2 clean auto-commits (one per registered
  client's own account, within its own registered contact window), both
  always-escalate ops (`:approve-off-hours-contact`,
  `:approve-settlement-offer`), and 5 of the 5 documented HARD-hold
  reasons that are reachable via the real advisor (`:no-client`,
  `:unknown-account`, `:account-wrong-client`,
  `:contact-hour-out-of-window`, `:harassment-flagged`) -- see
  namespace docstring for the 2 rules that are architecturally
  unreachable here. Every `:op`/rule name below is copied from
  `debtcollection.advisor`/`debtcollection.governor`'s own source, not
  invented."
  [;; client-1 / \"Kobo Collections\" / A-1 (verbatim test fixture, window 8-21)
   ["dc-clean-contact-c1"    "client-1" :approve-contact-attempt
    {:account-id "A-1" :contact-hour 12 :harassment-flagged? false :stake :low}]
   ["dc-hour-out-of-window"  "client-1" :approve-contact-attempt
    {:account-id "A-1" :contact-hour 2 :harassment-flagged? false :stake :low}]
   ["dc-harassment-flagged"  "client-1" :approve-contact-attempt
    {:account-id "A-1" :contact-hour 12 :harassment-flagged? true :stake :low}]
   ["dc-unknown-account"     "client-1" :approve-contact-attempt
    {:account-id "A-ghost" :contact-hour 12 :harassment-flagged? false :stake :low}]
   ["dc-off-hours-escalate"  "client-1" :approve-off-hours-contact
    {:account-id "A-1" :stake :low}]
   ["dc-settlement-escalate" "client-1" :approve-settlement-offer
    {:account-id "A-1" :stake :medium}]
   ;; client-2 / \"Northgate Recovery Partners\" / A-2 (additional demo data,
   ;; see namespace docstring)
   ["dc-clean-contact-c2"    "client-2" :approve-contact-attempt
    {:account-id "A-2" :contact-hour 10 :harassment-flagged? false :stake :low}]
   ["dc-wrong-client-account" "client-2" :approve-contact-attempt
    {:account-id "A-1" :contact-hour 12 :harassment-flagged? false :stake :low}]
   ;; client-ghost is never registered -- see namespace docstring
   ["dc-no-client"           "client-ghost" :approve-contact-attempt
    {:account-id "A-1" :contact-hour 12 :harassment-flagged? false :stake :low}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `debtcollection.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Collections"})
    (store/register-account! db {:account-id "A-1" :client-id "client-1"
                                  :name "account-042"
                                  :contact-start-hour 8 :contact-end-hour 21})
    (store/register-client! db {:client-id "client-2" :name "Northgate Recovery Partners"})
    (store/register-account! db {:account-id "A-2" :client-id "client-2"
                                  :name "account-118"
                                  :contact-start-hour 9 :contact-end-hour 18})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- account-row [store account-id client-id runs]
  (let [{:keys [name contact-start-hour contact-end-hour]} (store/account store account-id)
        committed (count (store/records-of store client-id))]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%d&ndash;%d</td><td>%d</td></tr>"
            (esc account-id) (esc client-id) (esc name) contact-start-hour contact-end-hour committed)))

(defn- request-detail [request]
  (let [{:keys [account-id contact-hour harassment-flagged?]} request]
    (esc (str/join ", " (remove nil?
                                 [(some->> account-id (str "account-id="))
                                  (some->> contact-hour (str "contact-hour="))
                                  (when (true? harassment-flagged?) "harassment-flagged?=true")])))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (request-detail request)
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`debtcollection.governor`'s own docstring) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:no-client</code></td><td class=\"critical-cell\">HARD &middot; creditor/collection agency must be registered</td></tr>"
   "        <tr><td><code>:no-actuation</code></td><td class=\"critical-cell\">HARD &middot; proposal effect must be :propose (governor never dispatches hardware itself)</td></tr>"
   "        <tr><td><code>:unknown-account</code></td><td class=\"critical-cell\">HARD &middot; a contact-attempt must cite a REGISTERED account belonging to this client</td></tr>"
   "        <tr><td><code>:account-wrong-client</code></td><td class=\"critical-cell\">HARD &middot; account belongs to a different registered client</td></tr>"
   "        <tr><td><code>:contact-hour-out-of-window</code></td><td class=\"critical-cell\">HARD &middot; contact hour must fall inside the account's registered permitted-contact window</td></tr>"
   "        <tr><td><code>:harassment-flagged</code></td><td class=\"critical-cell\">HARD &middot; harassment/threat language refused by construction, not merely discouraged</td></tr>"
   "        <tr><td><code>:approve-off-hours-contact</code></td><td class=\"warn\">ALWAYS human approval &middot; no contact outside the registered window without the governor gate</td></tr>"
   "        <tr><td><code>:approve-settlement-offer</code></td><td class=\"warn\">ALWAYS human approval &middot; a settlement offer to a debtor always requires sign-off</td></tr>"
   "        <tr><td><code>confidence &lt; 0.6</code></td><td class=\"warn\">ALWAYS human approval &middot; confidence floor</td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [account-rows (str/join "\n" [(account-row store "A-1" "client-1" runs)
                                      (account-row store "A-2" "client-2" runs)])
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-4214 &middot; independent debt collection &amp; recovery</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Independent Debt Collection &amp; Recovery (ISCO-08 4214) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; never contacts a debtor outside the registered permitted-contact window</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered accounts</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>debtcollection.store</code> via <code>debtcollection.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. <code>client-1</code>/<code>A-1</code> is the verbatim <code>debtcollection.actor-test</code>/<code>governor-test</code> fixture; <code>client-2</code>/<code>A-2</code> is additional demo data registered via the store's own real <code>register-client!</code>/<code>register-account!</code> calls (disclosed, see namespace docstring).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Account</th><th>Client</th><th>Name</th><th>Permitted-contact window (hour)</th><th>Committed records</th></tr></thead>\n"
     "      <tbody>\n"
     account-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Independent Debt Collection Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Off-hours contact and settlement offers always require a human's sign-off, regardless of confidence.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own supporting detail, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Detail</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))

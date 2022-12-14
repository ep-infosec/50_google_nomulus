#standardSQL
  -- Copyright 2017 The Nomulus Authors. All Rights Reserved.
  --
  -- Licensed under the Apache License, Version 2.0 (the "License");
  -- you may not use this file except in compliance with the License.
  -- You may obtain a copy of the License at
  --
  --     http://www.apache.org/licenses/LICENSE-2.0
  --
  -- Unless required by applicable law or agreed to in writing, software
  -- distributed under the License is distributed on an "AS IS" BASIS,
  -- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  -- See the License for the specific language governing permissions and
  -- limitations under the License.

  -- This query gathers all non-canceled billing events for a given
  -- YEAR_MONTH in yyyy-MM format.

SELECT
  __key__.id AS id,
  billingTime,
  eventTime,
  BillingEvent.clientId AS registrarId,
  RegistrarData.accountId AS billingId,
  RegistrarData.poNumber AS poNumber,
  tld,
  reason as action,
  targetId as domain,
  BillingEvent.domainRepoId as repositoryId,
  IFNULL(periodYears, 0) as years,
  BillingEvent.currency AS currency,
  BillingEvent.amount as amount,
  -- We'll strip out non-useful flags downstream
  ARRAY_TO_STRING(flags, " ") AS flags
FROM (
  SELECT
    *,
    -- We store cost as "CURRENCY AMOUNT" such as "JPY 800" or "USD 20.00"
    SPLIT(cost, ' ')[OFFSET(0)] AS currency,
    SPLIT(cost, ' ')[OFFSET(1)] AS amount,
    -- Extract everything after the first dot in the domain as the TLD
    REGEXP_EXTRACT(targetId, r'[.](.+)') AS tld,
    REGEXP_REPLACE(SPLIT(__key__.path, ', ')[OFFSET(1)], '"', '')
        AS domainRepoId,
    COALESCE(cancellationMatchingBillingEvent.path,
        __key__.path) AS cancellationMatchingPath
  FROM
    `my-project-id.latest_datastore_export.OneTime`
    -- Only include real TLDs (filter prober data)
  WHERE
    REGEXP_EXTRACT(targetId, r'[.](.+)') IN (
    SELECT
      tldStr
    FROM
      `my-project-id.latest_datastore_export.Registry`
    WHERE
      invoicingEnabled IS TRUE) ) AS BillingEvent
  -- Gather billing ID from registrar table
  -- This is a 'JOIN' as opposed to 'LEFT JOIN' to filter out
  -- non-billable registrars
JOIN (
  SELECT
    __key__.name AS clientId,
    billingIdentifier,
    IFNULL(poNumber, '') AS poNumber,
    r.billingAccountMap.currency[SAFE_OFFSET(index)] AS currency,
    r.billingAccountMap.accountId[SAFE_OFFSET(index)] AS accountId
  FROM
    `my-project-id.latest_datastore_export.Registrar` AS r,
    UNNEST(GENERATE_ARRAY(0, ARRAY_LENGTH(r.billingAccountMap.currency) - 1))
        AS index
  WHERE billingAccountMap IS NOT NULL
  AND type = 'REAL') AS RegistrarData
ON
  BillingEvent.clientId = RegistrarData.clientId
  AND BillingEvent.currency = RegistrarData.currency
  -- Gather cancellations
LEFT JOIN (
  SELECT __key__.id AS cancellationId,
  COALESCE(refOneTime.path, refRecurring.path) AS cancelledEventPath,
  eventTime as cancellationTime,
  billingTime as cancellationBillingTime
  FROM
  (SELECT
      *,
      -- Count everything after first dot as TLD (to support multi-part TLDs).
      REGEXP_EXTRACT(targetId, r'[.](.+)') AS tld
    FROM
      `my-project-id.latest_datastore_export.Cancellation`)
) AS Cancellation
ON BillingEvent.cancellationMatchingPath = Cancellation.cancelledEventPath
AND BillingEvent.billingTime = Cancellation.cancellationBillingTime
WHERE billingTime BETWEEN TIMESTAMP('2017-10-01 00:00:00.000000')
  AND TIMESTAMP('2017-10-31 23:59:59.999999')
-- Filter out canceled events
AND Cancellation.cancellationId IS NULL
ORDER BY
  billingTime DESC,
  id,
  tld

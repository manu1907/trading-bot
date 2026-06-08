create table if not exists trading_projection_balances (
    state_key varchar(512) primary key,
    provider varchar(64) not null,
    environment varchar(64) not null,
    account varchar(128) not null,
    market varchar(128) not null,
    asset varchar(128) not null,
    wallet_balance varchar(128),
    cross_wallet_balance varchar(128),
    available_balance varchar(128),
    balance_delta varchar(128),
    update_reason varchar(128),
    updated_at varchar(64) not null,
    event_id varchar(512)
);

create table if not exists trading_projection_positions (
    state_key varchar(512) primary key,
    provider varchar(64) not null,
    environment varchar(64) not null,
    account varchar(128) not null,
    market varchar(128) not null,
    symbol varchar(128) not null,
    position_side varchar(64) not null,
    position_amount varchar(128),
    entry_price varchar(128),
    mark_price varchar(128),
    unrealized_pnl varchar(128),
    leverage varchar(128),
    margin_type varchar(128),
    isolated_margin varchar(128),
    updated_at varchar(64) not null,
    event_id varchar(512)
);

alter table trading_projection_positions add column if not exists leverage varchar(128);
alter table trading_projection_positions add column if not exists margin_type varchar(128);
alter table trading_projection_positions add column if not exists isolated_margin varchar(128);

create table if not exists trading_projection_orders (
    state_key varchar(512) primary key,
    provider varchar(64) not null,
    environment varchar(64) not null,
    account varchar(128) not null,
    market varchar(128) not null,
    symbol varchar(128) not null,
    command_id varchar(512),
    client_order_id varchar(256) not null,
    exchange_order_id varchar(256),
    status varchar(64),
    exchange_status varchar(64),
    price varchar(128),
    original_quantity varchar(128),
    executed_quantity varchar(128),
    average_price varchar(128),
    cumulative_quote varchar(128),
    update_source varchar(64),
    execution_type varchar(64),
    managed_by_bot boolean not null default false,
    external_intervention boolean not null default false,
    intervention_reason varchar(256),
    updated_at varchar(64) not null,
    event_id varchar(512)
);

create table if not exists trading_projection_risks (
    state_key varchar(512) primary key,
    provider varchar(64) not null,
    environment varchar(64) not null,
    account varchar(128) not null,
    market varchar(128) not null,
    risk_scope varchar(128) not null,
    symbol varchar(128),
    underlying varchar(128),
    risk_level varchar(128),
    delta varchar(128),
    gamma varchar(128),
    theta varchar(128),
    vega varchar(128),
    margin_balance varchar(128),
    maintenance_margin varchar(128),
    updated_at varchar(64) not null,
    event_id varchar(512)
);

create table if not exists trading_projection_pause_governance (
    state_key varchar(512) primary key,
    provider varchar(64) not null,
    environment varchar(64) not null,
    account varchar(128) not null,
    market varchar(128) not null,
    pause_scope varchar(64) not null,
    pause_target varchar(256) not null,
    symbol varchar(128),
    remediation_id varchar(512) not null,
    source_scope varchar(128) not null,
    action varchar(128) not null,
    intervention_reason varchar(512),
    reasons varchar(2048),
    decided_by varchar(256),
    decision_reason varchar(2048),
    attributes varchar(4096),
    active boolean not null default true,
    updated_at varchar(64) not null,
    event_id varchar(512)
);

create table if not exists trading_projection_applied_event_ids (
    sequence_number integer primary key,
    event_id varchar(512) not null unique
);

-- Unassigning (by hand, or by connecting or disconnecting) now clears the address and keeps the row, so the time of
-- the last invite survives and the one-email-a-minute limit cannot be bypassed by deleting and assigning again.
alter table coderepository.assignment alter column email drop not null;

comment on column coderepository.assignment.email is 'Who was asked; null once unassigned (the row keeps assigned_at).';
comment on column coderepository.assignment.assigned_at is 'When the last invite was sent.';

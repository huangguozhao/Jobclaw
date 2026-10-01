# JobClaw Admin UI Design Standard

This standard defines the baseline for JobClaw admin pages. It uses TheFrontKit HR Dashboard Kit - Recruitment Pipeline as the main reference: dense operational pages, top-level business objects as cards, pipeline/status information as compact rows, and detail work in side panels.

## Reference Direction

- Primary reference: TheFrontKit HR Dashboard Kit - Recruitment Pipeline.
- Secondary references: mature recruitment dashboards and HR admin templates that emphasize candidate/job operation efficiency.
- JobClaw mapping: recruitment pipeline concepts map to job collection tasks, provider settings, agent workflows, candidates, job drafts, and publishing states.

## Layout Rules

- Use one clear page header with the page title, short operational description, and primary action on the right.
- Use top-level cards only for first-class business objects, such as a job, candidate, LLM provider, agent, task, or draft.
- Do not create a large card for every nested attribute. Nested data should use rows, dividers, compact metadata, tables, or select lists.
- Keep admin pages dense and scannable. Prefer restrained borders, `divide-y`, and whitespace over repeated framed boxes.
- Use right-side sheets for create/edit/detail workflows that do not require leaving the page.
- Use alert dialogs for destructive confirmation. Do not use `window.confirm`.

## Component Rules

- Cards represent objects, not layout decoration.
- Forms use label-above-input with `gap-2`; group only related required fields.
- Inputs shown to admin users must be business-relevant. Hide internal or unsupported fields instead of exposing them.
- Use selects for finite options, toggles for boolean settings, and icon buttons for row actions.
- Model/provider lists must show built-in metadata such as free/paid or text/vision as labels, not as fields the admin has to invent.
- Avoid nested cards, oversized empty panels, decorative gradients, and repeated large bordered containers.

## Interaction Rules

- Every click must have a visible result: open a panel, change state, show validation, execute an action, or close a confirmation.
- Save actions must validate required fields before calling the API and show field-level errors.
- Loading, empty, and error states must be visible in the page body.
- Destructive actions must support cancel and confirm.
- After successful save/delete, refresh the source data and keep the page in a consistent state.
- Do not add mock controls unless the backend behavior exists.

## LLM Provider Page Rules

- One provider equals one card.
- Models live inside the provider card as a select list, with type and billing tags displayed from configuration.
- The add/edit provider workflow uses a right-side sheet.
- The sheet contains only provider identity, API access, chat endpoint, and text/vision model list.
- Billing type is read-only metadata from built-in or imported model definitions. It is not an admin input.
- Vision models are the multimodal models for this page.

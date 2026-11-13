// DC-S01-05 The no-access page.
import { useQuery } from "@tanstack/react-query";
import { SignedInLayout } from "../layout/SignedInLayout";
import { brandingQuery, tenantQuery } from "../session/queries";

export function NoAccessPage() {
  return (
    <SignedInLayout
      access="no-access"
      screen="No access"
      topics={[]}
      live={false}
    >
      <NoAccess />
    </SignedInLayout>
  );
}

function NoAccess() {
  const branding = useQuery(brandingQuery);
  const tenant = useQuery(tenantQuery);
  const product = (tenant.data ?? branding.data)?.product_display_name ?? "";
  return (
    <>
      <h1>No access</h1>
      <p>
        There is nothing to show here for your account. Open the link in your{" "}
        {product} message to acknowledge or approve.
      </p>
    </>
  );
}
